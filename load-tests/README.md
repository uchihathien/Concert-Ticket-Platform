# Load test (k6)

Tách khỏi mọi lệnh test thường: không chạy trong `./mvnw verify`, không chạy trong CI mặc định.
Chúng tạo hold và đơn **thật** trên hệ thống đích, nên chỉ chạy vào môi trường dev/staging —
`lib/common.js` từ chối mọi host không phải localhost, `*.localhost`, `*.test` hay tên có nhãn
`staging|test|dev|qa|sandbox` (ghi đè bằng `ALLOW_REMOTE=1` khi thật sự chắc chắn).

## Kịch bản

| Tệp | Đo / kiểm gì | Cần đăng nhập |
| --- | --- | --- |
| `k6/discovery.js` | Danh sách + tìm kiếm sự kiện, chi tiết, sơ đồ ghế. Ngưỡng p95: 300 ms (catalog), 500 ms (sơ đồ ghế). Bất biến: số chỗ đứng còn lại ∈ [0, sức chứa]. | Không |
| `k6/hold-contention.js` | N người cùng giữ đúng một nhóm ghế trong một khoảnh khắc. Ngưỡng `holds_won == 1`: không bán trùng ở tầng HTTP. Teardown: ghế về `AVAILABLE` sau khi nhả. | Có — ≥ 2 token, mỗi người một token |
| `k6/checkout.js` | Giữ chỗ → đặt đơn → gửi lại cùng `Idempotency-Key` (phải trả đúng đơn cũ) → tuỳ chọn giả lập chuyển khoản → đơn `PAID`. | Có |

## Chuẩn bị

1. Cài k6: `winget install k6` (Windows) hoặc xem https://grafana.com/docs/k6/latest/set-up/install-k6/.
2. Dựng hệ thống theo README gốc (hạ tầng + các service + dữ liệu demo), payment-service với
   `PAYMENT_SANDBOX=true` nếu muốn chạy nhánh thanh toán.
3. Lấy access token cho vài người dùng thử từ Keycloak dev (realm `nexaticket`), mỗi dòng một token
   vào `tokens.txt`. **Không commit tệp này.** Ví dụ với một client cho phép password grant ở môi
   trường dev:

   ```bash
   curl -s -d grant_type=password -d client_id=$CLIENT -d username=$U -d password=$P \
     http://localhost:8081/realms/nexaticket/protocol/openid-connect/token | jq -r .access_token >> tokens.txt
   ```

4. Rate limit giữ chỗ của gateway là 5 rps / người (`RATE_LIMIT_HOLD_RPS`). Kịch bản tranh chấp
   dùng mỗi VU một người nên không đụng trần này; nếu thấy cảnh báo 429, tăng biến đó ở môi trường thử.

## payOS giả — chạy `checkout.js` mà không chạm payOS thật

payOS không có sandbox: mọi lần payment-service gọi payOS là gọi hệ thống thật, mỗi đơn mở một link thanh toán
thật. `checkout.js` tạo hàng chục đơn mỗi phút, nên chạy nó với payOS giả:

```bash
node load-tests/fake-payos/server.mjs        # cổng 9999, checksum key "test-checksum-key"

# payment-service: KHÔNG nạp .env (khoá thật không được có trong tiến trình)
PAYOS_BASE_URL=http://localhost:9999 PAYOS_CLIENT_ID=test PAYOS_API_KEY=test PAYOS_CHECKSUM_KEY=test-checksum-key PAYMENT_SANDBOX=true  <chạy payment-service>
```

Server giả ký phản hồi đúng thuật toán payOS (nên `PayosHttpGateway` kiểm chữ ký thật sự chạy) và kiểm chữ ký
request. `curl http://localhost:9999/_stats` cho biết số link đã tạo / huỷ. Tiền không bao giờ vào: dùng
`-e PAYMENT_INTERNAL_URL=http://localhost:8095` (cổng ứng dụng, không phải cổng quản trị 9095) để script gọi
`simulate-transfer`. Số nên chạy: `RATE` thấp và token của nhiều người dùng, vì hạn mức mua là theo người dùng.

## Chạy

```bash
k6 run load-tests/k6/discovery.js
k6 run -e PEAK_VUS=200 load-tests/k6/discovery.js
k6 run -e TOKENS_FILE=tokens.txt -e SEATS=4 load-tests/k6/hold-contention.js
k6 run -e TOKENS_FILE=tokens.txt -e STANDING_ZONE=GA load-tests/k6/checkout.js
k6 run -e TOKENS_FILE=tokens.txt -e SEATED_ZONE=A -e PAYMENT_INTERNAL_URL=http://localhost:8095 load-tests/k6/checkout.js
```

Biến chung: `BASE_URL` (mặc định `http://localhost:8080`, gateway), `SESSION_ID` / `EVENT_SLUG`
để chỉ định suất thay vì tự chọn suất đang mở bán đầu tiên.

## Quan hệ với test tự động

Bán trùng đã được kiểm **xác định** ở tầng service bằng integration test với PostgreSQL thật
(`inventory-service`: `SeatHoldConcurrencyIT`, `OversellBackstopIT`). k6 bổ sung phần những test đó
không chạm tới: gateway, Redis gate, pool kết nối và độ trễ dưới tải. Một lần k6 xanh không thay
cho các IT đó, và ngược lại.
