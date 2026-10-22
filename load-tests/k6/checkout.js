// Luồng mua: giữ chỗ → đặt đơn → (tuỳ chọn) giả lập chuyển khoản → đơn PAID.
//
//   k6 run -e TOKENS_FILE=tokens.txt load-tests/k6/checkout.js
//   # thêm thanh toán giả lập (chỉ khi payment-service chạy với PAYMENT_SANDBOX=true):
//   k6 run -e TOKENS_FILE=tokens.txt -e PAYMENT_INTERNAL_URL=http://localhost:8095 load-tests/k6/checkout.js
//
// Mỗi vòng cũng kiểm tính idempotent của POST /v1/orders: gửi lại CÙNG Idempotency-Key phải trả
// lại đúng đơn cũ, không mở đơn thứ hai — đây là lưới chống "khách bấm hai lần".
//
// Đơn không trả tiền sẽ tự hết hạn theo cửa sổ thanh toán; kịch bản huỷ chúng ở cuối vòng để không
// giữ ghế lâu hơn cần thiết.

import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import exec from 'k6/execution';
import { BASE_URL, authHeaders, guardTarget, loadTokens, pickOnSaleSession, uuid } from './lib/common.js';

const TOKENS = loadTokens();
const PAYMENT = (__ENV.PAYMENT_INTERNAL_URL || '').replace(/\/+$/, '');
const paidLatency = new Trend('order_paid_latency_ms', true);

export const options = {
  scenarios: {
    buyers: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 2),
      timeUnit: '1s',
      duration: __ENV.DURATION || '1m',
      preAllocatedVUs: Math.max(TOKENS.length, 1),
    },
  },
  thresholds: {
    'http_req_duration{name:place_order}': ['p(95)<1500'],
    'http_req_duration{name:place_hold}': ['p(95)<800'],
    checks: ['rate>0.95'],
  },
};

export function setup() {
  guardTarget();
  if (TOKENS.length === 0) fail('Cần TOKENS hoặc TOKENS_FILE.');
  holdRequest(); // kiểm cấu hình một lần trước khi tạo tải
  return pickOnSaleSession();
}

export default function (target) {
  const token = TOKENS[exec.scenario.iterationInTest % TOKENS.length];

  const hold = http.post(
    `${BASE_URL}/v1/sessions/${target.sessionId}/holds`,
    JSON.stringify(holdRequest()),
    { headers: authHeaders(token, { 'Idempotency-Key': uuid() }), tags: { name: 'place_hold' } },
  );
  if (!check(hold, { 'giữ chỗ 201': (r) => r.status === 201 })) {
    return; // hết chỗ hoặc vượt hạn mức — hợp lệ khi tải cao, không phải lỗi hệ thống
  }

  const key = uuid();
  const body = JSON.stringify({ holdId: hold.json('holdId') });
  const order = http.post(`${BASE_URL}/v1/orders`, body, {
    headers: authHeaders(token, { 'Idempotency-Key': key }),
    tags: { name: 'place_order' },
  });
  check(order, {
    'đặt đơn 201': (r) => r.status === 201,
    'tổng tiền do server tính, dương': (r) => r.json('totalVnd') > 0,
  });
  if (order.status !== 201) return;
  const orderId = order.json('orderId');

  const replay = http.post(`${BASE_URL}/v1/orders`, body, {
    headers: authHeaders(token, { 'Idempotency-Key': key }),
    tags: { name: 'place_order_replay' },
  });
  check(replay, { 'gửi lại cùng khoá trả đúng đơn cũ': (r) => r.status < 300 && r.json('orderId') === orderId });

  if (PAYMENT) {
    const started = Date.now();
    http.post(`${PAYMENT}/internal/payment-intents/${orderId}/simulate-transfer`, null, {
      tags: { name: 'simulate_transfer' },
    });
    let status = '';
    for (let i = 0; i < 20 && status !== 'PAID'; i++) {
      sleep(0.5);
      status = http.get(`${BASE_URL}/v1/orders/${orderId}`, { headers: authHeaders(token) }).json('status');
    }
    check(status, { 'đơn chuyển PAID sau khi nhận tiền': (s) => s === 'PAID' });
    if (status === 'PAID') paidLatency.add(Date.now() - started);
  } else {
    http.post(`${BASE_URL}/v1/orders/${orderId}/cancel`, null, { headers: authHeaders(token) });
  }
}

/** Một vé đứng nếu có khu đứng, không thì một vé ngồi do hệ thống chọn — giảm tranh chấp giữa VU. */
function holdRequest() {
  const zone = __ENV.STANDING_ZONE;
  if (zone) return { standing: [{ zoneCode: zone, quantity: 1 }] };
  const seated = __ENV.SEATED_ZONE;
  if (seated) return { seatedZones: [{ zoneCode: seated, quantity: 1 }] };
  fail('Đặt STANDING_ZONE hoặc SEATED_ZONE (mã khu của suất) để kịch bản biết giữ chỗ ở đâu.');
}
