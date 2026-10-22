// payOS giả cho load test và thử luồng mua vé ở máy dev.
//
// Vì sao có: payOS KHÔNG có môi trường sandbox. Mọi lần payment-service gọi
// https://api-merchant.payos.vn là gọi hệ thống thật bằng tài khoản thật — mỗi đơn mở một link
// thanh toán thật. Server này nói đúng giao thức payOS mà `PayosHttpGateway` dùng, nên trỏ
// `PAYOS_BASE_URL` vào đây là chạy trọn luồng đặt vé mà không chạm vào payOS.
//
//   node load-tests/fake-payos/server.mjs                  # cổng 9999, checksum key "test-checksum-key"
//   PORT=9999 PAYOS_CHECKSUM_KEY=khoa-thu node load-tests/fake-payos/server.mjs
//
// Chạy payment-service với (KHÔNG nạp .env — khoá thật không được có trong tiến trình):
//
//   PAYOS_BASE_URL=http://localhost:9999 PAYOS_CLIENT_ID=test PAYOS_API_KEY=test \
//   PAYOS_CHECKSUM_KEY=test-checksum-key PAYMENT_SANDBOX=true
//
// Giả ở mức nào, và KHÔNG giả gì:
//   - Có: tạo link, tra trạng thái, huỷ link, đăng ký webhook. Phản hồi được ký HMAC-SHA256 đúng thuật
//     toán payOS nên `PayosHttpGateway` kiểm chữ ký phản hồi thật sự chạy. Request tạo link cũng bị kiểm
//     chữ ký và độ dài `description` (≤ 9) — gửi sai là bị từ chối như payOS thật.
//   - Không: tiền không bao giờ vào. Hoàn tất thanh toán bằng
//     POST payment-service /internal/payment-intents/{orderId}/simulate-transfer (cần PAYMENT_SANDBOX=true).
//   - Không: không gọi webhook ngược lại payment-service.
import crypto from 'node:crypto';
import http from 'node:http';

const PORT = Number(process.env.PORT || 9999);
const KEY = process.env.PAYOS_CHECKSUM_KEY || 'test-checksum-key';
const ACCOUNT = { bin: '970422', accountNumber: 'FAKE0000000001', accountName: 'NEXATICKET FAKE PAYOS' };

/** orderCode -> link */
const links = new Map();
const stats = { created: 0, cancelled: 0, fetched: 0, rejected: 0 };

// --- Chữ ký: cùng thuật toán với PayosSignature.java -------------------------------------------

function sortedJson(value) {
  if (Array.isArray(value)) return '[' + value.map(sortedJson).join(',') + ']';
  if (value && typeof value === 'object') {
    return '{' + Object.keys(value).sort().map((k) => JSON.stringify(k) + ':' + sortedJson(value[k])).join(',') + '}';
  }
  return value === null || value === undefined ? 'null' : JSON.stringify(value);
}

function render(value) {
  if (value === null || value === undefined) return '';
  if (typeof value === 'number') return String(value);
  if (Array.isArray(value) || typeof value === 'object') return sortedJson(value);
  const text = String(value);
  return text === 'null' || text === 'undefined' ? '' : text;
}

function signData(data) {
  const canonical = Object.keys(data)
    .sort()
    .map((k) => `${k}=${render(data[k])}`)
    .join('&');
  return crypto.createHmac('sha256', KEY).update(canonical).digest('hex');
}

function signCreateRequest(b) {
  const canonical = `amount=${b.amount}&cancelUrl=${b.cancelUrl}&description=${b.description}&orderCode=${b.orderCode}&returnUrl=${b.returnUrl}`;
  return crypto.createHmac('sha256', KEY).update(canonical).digest('hex');
}

// --- Phản hồi ----------------------------------------------------------------------------------

function ok(res, data) {
  const body = { code: '00', desc: 'success', data, signature: signData(data) };
  send(res, 200, body);
}

function fail(res, code, desc, status = 200) {
  stats.rejected++;
  send(res, status, { code, desc, data: null });
}

function send(res, status, body) {
  const text = JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(text) });
  res.end(text);
}

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'));
      } catch {
        resolve(null);
      }
    });
  });
}

function linkData(link) {
  return {
    id: link.paymentLinkId,
    orderCode: link.orderCode,
    amount: link.amount,
    amountPaid: 0,
    amountRemaining: link.amount,
    status: link.status,
    createdAt: link.createdAt,
    transactions: [],
    canceledAt: link.canceledAt ?? null,
    cancellationReason: link.cancellationReason ?? null,
  };
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const path = url.pathname;

  if (path === '/_stats') return send(res, 200, { ...stats, links: links.size });
  if (path === '/_reset' && req.method === 'POST') {
    links.clear();
    Object.assign(stats, { created: 0, cancelled: 0, fetched: 0, rejected: 0 });
    return send(res, 200, { reset: true });
  }

  // payOS đòi hai header này; thiếu là 401 như thật.
  if (!req.headers['x-client-id'] || !req.headers['x-api-key']) {
    return fail(res, '401', 'Thiếu x-client-id hoặc x-api-key', 401);
  }

  if (req.method === 'POST' && path === '/v2/payment-requests') {
    const b = await readBody(req);
    if (!b) return fail(res, '20', 'Body không phải JSON');
    for (const f of ['orderCode', 'amount', 'description', 'cancelUrl', 'returnUrl', 'signature']) {
      if (b[f] === undefined || b[f] === null || b[f] === '') return fail(res, '20', `Thiếu trường ${f}`);
    }
    if (String(b.description).length > 9) return fail(res, '20', 'description tối đa 9 ký tự');
    if (!(b.amount > 0)) return fail(res, '20', 'amount phải > 0');
    if (signCreateRequest(b) !== String(b.signature).toLowerCase()) return fail(res, '201', 'Chữ ký request không hợp lệ');
    if (links.has(b.orderCode)) return fail(res, '231', 'Đơn thanh toán đã tồn tại');

    const link = {
      orderCode: b.orderCode,
      amount: b.amount,
      description: b.description,
      status: 'PENDING',
      paymentLinkId: crypto.randomUUID().replace(/-/g, ''),
      createdAt: new Date().toISOString(),
      expiredAt: b.expiredAt ?? null,
    };
    links.set(b.orderCode, link);
    stats.created++;
    return ok(res, {
      ...ACCOUNT,
      amount: link.amount,
      description: link.description,
      orderCode: link.orderCode,
      currency: 'VND',
      paymentLinkId: link.paymentLinkId,
      status: link.status,
      expiredAt: link.expiredAt,
      checkoutUrl: `http://localhost:${PORT}/web/${link.paymentLinkId}`,
      qrCode: `00020101021238FAKE${link.orderCode}6304ABCD`,
    });
  }

  const m = path.match(/^\/v2\/payment-requests\/(\d+)(\/cancel)?$/);
  if (m) {
    const link = links.get(Number(m[1]));
    if (!link) return fail(res, '101', 'Không tìm thấy đơn thanh toán');
    if (req.method === 'GET' && !m[2]) {
      stats.fetched++;
      return ok(res, linkData(link));
    }
    if (req.method === 'POST' && m[2]) {
      const b = (await readBody(req)) || {};
      if (link.status === 'PENDING') {
        link.status = 'CANCELLED';
        link.canceledAt = new Date().toISOString();
        link.cancellationReason = b.cancellationReason ?? null;
        stats.cancelled++;
      }
      return ok(res, linkData(link));
    }
  }

  if (req.method === 'POST' && path === '/confirm-webhook') {
    const b = (await readBody(req)) || {};
    return ok(res, { webhookUrl: b.webhookUrl ?? '', ...ACCOUNT });
  }

  return fail(res, '404', `payOS giả không có ${req.method} ${path}`, 404);
});

server.listen(PORT, () => console.log(`payOS giả nghe cổng ${PORT} (checksum key: ${KEY === 'test-checksum-key' ? 'mặc định' : 'tuỳ chỉnh'})`));
