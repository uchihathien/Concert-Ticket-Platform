// Phần dùng chung của các kịch bản k6.
//
// Mọi kịch bản đều đi qua api-gateway (cổng 8080 ở máy dev), giống hệt trình duyệt — đo service lẻ
// sẽ bỏ qua rate limit, JWT và định tuyến, tức là bỏ qua đúng những thứ hay nghẽn nhất.

import http from 'k6/http';
import { fail } from 'k6';

export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');

/**
 * Chặn chạy nhầm vào hệ thống thật.
 *
 * Load test giữ ghế và mở đơn THẬT: chạy vào production là khoá chỗ của khách thật suốt thời gian
 * giữ chỗ. Chỉ cho phép localhost hoặc tên miền có nhãn staging/test/dev; muốn khác thì phải nói
 * rõ bằng ALLOW_REMOTE=1.
 */
export function guardTarget() {
  const host = BASE_URL.replace(/^https?:\/\//, '').split(/[/:]/)[0];
  const safe =
    ['localhost', '127.0.0.1', '[::1]'].includes(host) ||
    host.endsWith('.localhost') ||
    host.endsWith('.test') ||
    /(^|[.-])(staging|test|dev|qa|sandbox)([.-]|$)/.test(host);
  if (!safe && __ENV.ALLOW_REMOTE !== '1') {
    fail(`Từ chối chạy vào ${host}: không phải môi trường thử. Đặt ALLOW_REMOTE=1 nếu chắc chắn.`);
  }
}

/**
 * Danh sách access token của người dùng thử, mỗi VU một người.
 *
 * Một token cho mọi VU sẽ đo sai: rate limit của gateway tính theo người dùng (giữ chỗ: 5 rps), và
 * hạn mức mua vé cũng theo người dùng — nên kết quả chỉ còn là "một người bấm rất nhanh".
 * Nguồn: TOKENS="t1,t2,..." hoặc TOKENS_FILE=đường/dẫn (mỗi dòng một token).
 */
export function loadTokens() {
  let raw = __ENV.TOKENS || '';
  if (!raw && __ENV.TOKENS_FILE) {
    raw = open(__ENV.TOKENS_FILE);
  }
  return raw
    .split(/[\s,]+/)
    .map((t) => t.trim())
    .filter(Boolean);
}

export function authHeaders(token, extra = {}) {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...extra };
}

/** UUID v4 cho Idempotency-Key — k6 không có crypto.randomUUID ở mọi phiên bản. */
export function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

/** Lấy một suất đang bán từ catalog công khai, để kịch bản không phải hard-code id. */
export function pickOnSaleSession() {
  if (__ENV.SESSION_ID) {
    return { sessionId: __ENV.SESSION_ID, slug: __ENV.EVENT_SLUG || null };
  }
  const list = http.get(`${BASE_URL}/v1/events?size=20`);
  if (list.status !== 200) {
    fail(`GET /v1/events trả ${list.status} — hệ thống chưa chạy hoặc chưa có dữ liệu demo`);
  }
  const now = Date.now();
  for (const card of list.json('items') || []) {
    const detail = http.get(`${BASE_URL}/v1/events/${card.slug}`);
    if (detail.status !== 200) continue;
    for (const s of detail.json('sessions') || []) {
      const open = !s.salesOpenAt || Date.parse(s.salesOpenAt) <= now;
      const notClosed = !s.salesCloseAt || Date.parse(s.salesCloseAt) > now;
      if (open && notClosed) return { sessionId: s.id, slug: card.slug };
    }
  }
  fail('Không tìm thấy suất nào đang mở bán. Đặt SESSION_ID để chỉ định.');
}
