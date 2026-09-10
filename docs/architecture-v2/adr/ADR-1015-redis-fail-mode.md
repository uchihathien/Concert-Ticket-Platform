# ADR-1015: Redis chết thì mỗi nơi hành xử một kiểu — và đó là chủ đích

- **Trạng thái**: Chấp nhận
- **Ngày**: 2026-09-10
- **Liên quan**: [ADR-0004](../../03-seat-checkout/adr/ADR-0004-seat-hold-consistency.md) (giữ chỗ ghế)

## Bối cảnh

Redis được ba nơi dùng, cho ba việc không liên quan gì tới nhau:

1. **Cổng giữ chỗ ghế** của inventory-service (Lua script, `RedisAvailabilityGate`)
2. **Rate limit** ở api-gateway (`RequestRateLimiter` của Spring Cloud Gateway)
3. **Phiên đăng nhập** của bốn app Next (`RedisRefreshTokenStore`)

Câu hỏi "Redis chết thì sao" vì thế không có một câu trả lời. Nó có ba, và trước ADR này thì hai
trong ba là **hành vi mặc định mà không ai chọn** — thứ nguy hiểm nhất trong một sự cố, vì lúc đó
người trực sẽ suy luận từ một mô hình sai.

## Quyết định

Giữ nguyên ba hành vi khác nhau, nhưng **ghi rõ ra**, vì hậu quả của việc đoán sai khác nhau ở ba
chỗ.

### 1. Giữ chỗ ghế — ĐÓNG (503)

Redis không trả lời thì `tryAcquire` ném `GateUnavailableException` và người dùng nhận 503 kèm mã
lỗi retryable. Không có fallback "chỉ dùng database".

**Vì sao không fallback:** database *có* chốt chặn oversell thật (unique index `uq_hold_item_active`),
nên về lý thuyết bỏ qua cổng Redis vẫn không bán trùng ghế. Nhưng cổng Redis tồn tại để 9.900
request thua cuộc không chạm database. Bỏ nó đi đúng vào lúc mở bán nghĩa là dồn toàn bộ tranh chấp
xuống Postgres, và Postgres sẽ chết ngay sau đó — đổi một sự cố Redis lấy một sự cố database, mà
database thì mang theo cả những phần hệ thống không liên quan gì tới việc bán vé.

Đây là ADR-0004, nhắc lại ở đây để không phải đi tìm.

### 2. Rate limit — MỞ (cho qua)

Redis không trả lời thì request **được cho qua**. Đây là hành vi của `RedisRateLimiter` trong
Spring Cloud Gateway: nó bắt lỗi, ghi log `Error calling rate limiter lua`, rồi trả về
`allowed = true`.

**Đã kiểm bằng cách đọc bytecode của `spring-cloud-gateway-server` 4.3.0**, không phải suy từ tài
liệu: nhánh `onErrorResume` phát ra `[1L, -1L]` và `1` ở vị trí đầu chính là cờ `allowed`.

**Vì sao giữ:** đóng lại nghĩa là một sự cố Redis biến thành **downtime toàn sàn** — không ai xem
được sự kiện, không ai mua được vé, kể cả những đường không cần Redis. Rate limit là lớp bảo vệ
chống lạm dụng, không phải lớp bảo vệ tính đúng đắn; mất nó vài phút là chấp nhận được, mất cả sàn
thì không.

**Cái giá phải biết:** trong lúc Redis chết, gateway không còn chống được dò tự động hay lũ request.
Nếu Redis chết *vì* đang bị tấn công thì hai việc đó cộng hưởng. Đây là lý do Redis phải có replica
và failover tự động ở production, chứ không chạy một node.

### 3. Phiên đăng nhập — ĐÓNG (không đăng nhập được)

Redis không trả lời thì không đọc được bản ghi phiên: người dùng không đăng nhập được, và phiên
đang chạy không làm mới được token khi access token hết hạn.

**Vì sao không có fallback in-memory:** rơi về bộ nhớ tiến trình khi Redis chết nghe có vẻ tử tế,
nhưng nó tạo ra những phiên **chỉ tồn tại trên một instance**. Load balancer đổi instance là người
dùng bị đăng xuất; Redis sống lại thì có hai nguồn sự thật, và việc thu hồi phiên — cả điểm của
kiến trúc này — im lặng ngừng hoạt động. Một lỗi đăng nhập rõ ràng tốt hơn một hệ thống thu hồi
phiên chỉ *trông như* đang chạy.

## Hệ quả

- Redis là **SPOF cho việc bán vé và cho việc đăng nhập**, không phải cho việc xem. Phải có replica
  + failover tự động ở production; một node là không đủ.
- `--appendonly yes` là bắt buộc (`prod.yml`). Không có AOF thì một lần khởi động lại Redis đăng
  xuất **mọi** người dùng cùng lúc — kể cả lần khởi động lại theo lịch để vá bảo mật.
- Người trực cần biết ba hành vi này trước khi vào sự cố. Đó là lý do trang này tồn tại, và là lý
  do `plan/production-checklist.md` trỏ vào đây.

## Đã cân nhắc và bỏ

- **Đóng cả ba cho nhất quán.** Nhất quán ở đây là một giá trị giả: nó đổi một sự cố Redis lấy
  downtime toàn sàn, để đạt được một tính chất (rate limit luôn đúng) không đáng giá bằng.
- **Mở cả ba cho hệ thống dễ sống.** Mở ở cổng giữ chỗ thì dồn tranh chấp xuống database; mở ở phiên
  đăng nhập thì phá cơ chế thu hồi. Cả hai đều đổi một sự cố nhìn thấy được lấy một sự cố im lặng.
- **Rate limit dự phòng trong bộ nhớ khi Redis chết.** Mỗi instance một hạn mức riêng, nên hạn mức
  thật nhân lên theo số instance và không ai biết con số đó là bao nhiêu. Thêm mã, thêm chỗ sai,
  đổi lại một sự bảo vệ không định lượng được.
