// Tải đọc: khám phá sự kiện và xem sơ đồ ghế — đường nóng nhất khi mở bán, không cần đăng nhập.
//
//   k6 run load-tests/k6/discovery.js
//   k6 run -e BASE_URL=http://localhost:8080 -e PEAK_VUS=200 load-tests/k6/discovery.js
//
// Ngưỡng là mục tiêu của plan (p95 dưới 300 ms cho đọc catalog, dưới 500 ms cho sơ đồ ghế); vượt
// ngưỡng thì k6 thoát mã khác 0, nên kịch bản này dùng được làm cổng chặn trong pipeline đêm.

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { BASE_URL, guardTarget, pickOnSaleSession } from './lib/common.js';

const PEAK = Number(__ENV.PEAK_VUS || 50);

export const options = {
  scenarios: {
    browse: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: PEAK },
        { duration: '1m', target: PEAK },
        { duration: '15s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:events_list}': ['p(95)<300'],
    'http_req_duration{name:event_detail}': ['p(95)<300'],
    'http_req_duration{name:seat_map}': ['p(95)<500'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  guardTarget();
  return pickOnSaleSession();
}

const QUERIES = ['', 'concert', 'rock', 'Hà Nội', 'jazz'];

export default function (target) {
  group('danh sách và tìm kiếm', () => {
    const q = QUERIES[Math.floor(Math.random() * QUERIES.length)];
    const res = http.get(`${BASE_URL}/v1/events?size=20&query=${encodeURIComponent(q)}`, {
      tags: { name: 'events_list' },
    });
    check(res, {
      'danh sách 200': (r) => r.status === 200,
      'có items là mảng': (r) => Array.isArray(r.json('items')),
      'size không vượt trần 60': (r) => (r.json('items') || []).length <= 60,
    });
  });

  if (target.slug) {
    group('chi tiết sự kiện', () => {
      const res = http.get(`${BASE_URL}/v1/events/${target.slug}`, { tags: { name: 'event_detail' } });
      check(res, { 'chi tiết 200': (r) => r.status === 200 });
    });
  }

  group('sơ đồ ghế', () => {
    // Trình duyệt luôn gửi Accept-Encoding; k6 thì KHÔNG tự gửi. Thiếu dòng này, mọi lần chạy đo sơ đồ ghế
    // KHÔNG NÉN — 5,3 MB mỗi lần với suất 16.796 ghế — và kết quả không phản ánh người dùng thật.
    const res = http.get(`${BASE_URL}/v1/sessions/${target.sessionId}/seats`, {
      headers: { 'Accept-Encoding': 'gzip' },
      tags: { name: 'seat_map' },
    });
    check(res, {
      'sơ đồ ghế 200': (r) => r.status === 200,
      'có availabilityVersion': (r) => typeof r.json('availabilityVersion') === 'number',
      'sơ đồ ghế được nén gzip': (r) => r.headers['Content-Encoding'] === 'gzip',
      // Bất biến: số còn lại của khu đứng không bao giờ âm và không vượt sức chứa.
      'khu đứng không âm, không vượt sức chứa': (r) =>
        (r.json('standingZones') || []).every((z) => z.available >= 0 && z.available <= z.capacity),
    });
  });

  sleep(Math.random() * 2 + 0.5);
}
