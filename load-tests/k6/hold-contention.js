// Tranh chấp ghế: nhiều người cùng giữ ĐÚNG MỘT nhóm ghế trong cùng một khoảnh khắc.
//
// Đây không phải phép đo tốc độ mà là phép thử bán trùng ở tầng HTTP, đi qua gateway thật,
// Redis gate thật và PostgreSQL thật. Bất biến:
//   - cả nhóm ghế có ĐÚNG MỘT người giữ thành công (201), mọi người khác nhận 409 (ngưỡng holds_won);
//   - người thắng giữ hold đủ lâu (HOLD_KEEP_S) để mọi request khác đã xong rồi mới nhả — nhả sớm
//     thì người đến sau giữ được hợp lệ và phép thử báo bán trùng giả;
//   - teardown: sau khi nhả, mọi ghế trở về AVAILABLE, không kẹt HELD.
//
//   k6 run -e TOKENS_FILE=tokens.txt -e SEATS=4 load-tests/k6/hold-contention.js
//
// Cần ít nhất 2 token (mỗi VU một người dùng). Kịch bản nhả mọi hold đã giữ ở cuối, nên chạy lại
// được ngay trên cùng một suất.

import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { BASE_URL, authHeaders, guardTarget, loadTokens, pickOnSaleSession, uuid } from './lib/common.js';

const TOKENS = loadTokens();
const SEATS = Number(__ENV.SEATS || 2);

const won = new Counter('holds_won');
const lost = new Counter('holds_conflict');
const unexpected = new Counter('holds_unexpected_status');

export const options = {
  scenarios: {
    stampede: {
      // Mỗi VU bắn đúng một lần, cùng lúc: đó là hình dạng của khoảnh khắc mở bán.
      executor: 'per-vu-iterations',
      vus: Math.max(TOKENS.length, 1),
      iterations: 1,
      maxDuration: '1m',
    },
  },
  thresholds: {
    holds_won: [`count==1`],
    holds_unexpected_status: ['count==0'],
  },
};

export function setup() {
  guardTarget();
  if (TOKENS.length < 2) {
    fail('Cần ít nhất 2 token trong TOKENS hoặc TOKENS_FILE — tranh chấp cần nhiều người dùng.');
  }
  const { sessionId } = pickOnSaleSession();
  const map = http.get(`${BASE_URL}/v1/sessions/${sessionId}/seats`);
  if (map.status !== 200) fail(`Không đọc được sơ đồ ghế: ${map.status}`);
  const free = (map.json('seats') || []).filter((s) => s.status === 'AVAILABLE');
  if (free.length < SEATS) fail(`Suất ${sessionId} chỉ còn ${free.length} ghế trống`);
  // Chọn ghế liền nhau trong danh sách để mọi VU tranh đúng một nhóm.
  return { sessionId, seatIds: free.slice(0, SEATS).map((s) => s.id) };
}

export default function (data) {
  const token = TOKENS[(exec.vu.idInTest - 1) % TOKENS.length];
  const res = http.post(
    `${BASE_URL}/v1/sessions/${data.sessionId}/holds`,
    JSON.stringify({ seatIds: data.seatIds }),
    { headers: authHeaders(token, { 'Idempotency-Key': uuid() }), tags: { name: 'place_hold' } },
  );
  if (res.status === 201) {
    won.add(1);
    check(res, { 'hold trả đúng các ghế đã xin': (r) => (r.json('seatIds') || []).length === data.seatIds.length });
    sleep(Number(__ENV.HOLD_KEEP_S || 10));
    http.del(`${BASE_URL}/v1/holds/${res.json('holdId')}`, null, { headers: authHeaders(token) });
  } else if (res.status === 409) {
    lost.add(1);
  } else if (res.status === 429) {
    // Rate limit của gateway — không phải bán trùng, nhưng làm phép thử yếu đi: báo ra để người
    // chạy biết mà nâng RATE_LIMIT_HOLD_* ở môi trường thử.
    lost.add(1);
    console.warn('429 từ gateway: rate limit giữ chỗ đang cắt bớt lượng tranh chấp');
  } else {
    unexpected.add(1);
    console.error(`Trạng thái lạ ${res.status}: ${res.body}`);
  }
}

export function teardown(data) {
  // Sau khi mọi hold đã nhả, các ghế phải về AVAILABLE — không kẹt HELD vĩnh viễn.
  const map = http.get(`${BASE_URL}/v1/sessions/${data.sessionId}/seats`);
  const states = (map.json('seats') || []).filter((s) => data.seatIds.includes(s.id)).map((s) => s.status);
  check(states, { 'ghế được nhả về AVAILABLE sau teardown': (s) => s.every((x) => x === 'AVAILABLE') });
}
