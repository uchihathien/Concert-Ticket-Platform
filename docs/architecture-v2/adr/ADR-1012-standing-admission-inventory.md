# ADR-1012: Vé đứng — đơn vị tồn kho ảo, cấp phát bằng `SKIP LOCKED`

**Status:** Accepted

## Context

Yêu cầu: hỗ trợ concert toàn ghế ngồi, concert vừa ngồi vừa đứng, và concert chỉ đứng.

Vé đứng khác vé ngồi ở một điểm cốt lõi: khách chọn **số lượng**, không chọn vị trí. Nhưng mỗi người vào cửa vẫn cần **một vé QR riêng** để soát, nên vẫn cần một đơn vị tồn kho cho mỗi người — chỉ là danh tính đơn vị đó không có ý nghĩa với khách.

Cơ chế chống oversell hiện tại (ADR-0004) dựa trên việc mỗi đơn vị tồn kho là một hàng riêng có unique constraint. Câu hỏi là có nên xây một cơ chế thứ hai kiểu bộ đếm cho vé đứng hay không.

## Decision

**Không xây cơ chế thứ hai.** Vé đứng dùng **đơn vị tồn kho ảo**: lúc materialize, sinh đúng `standing_capacity` hàng trong `session_seats` với `admission_type = 'STANDING'` và `seat_code` dạng `{zone}-GA-000137`. Chúng không hiện trên sơ đồ ghế.

Cấp phát bằng một câu lệnh PostgreSQL:

```sql
SELECT id FROM session_seats
 WHERE event_session_id = ? AND zone_code = ?
   AND admission_type = 'STANDING' AND status = 'AVAILABLE'
 ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED;
```

Trả về ít hơn số lượng yêu cầu ⇒ `ZONE_SOLD_OUT`, rollback.

**Không dùng Redis cho vé đứng.** Redis Lua tồn tại cho vé ngồi vì khách chỉ đích danh từng ghế và ta muốn từ chối nhanh trước khi chạm database. Với vé đứng không có gì để từ chối nhanh — khách không chỉ đích danh gì cả — và `SKIP LOCKED` đã cấp phát đúng trong một câu lệnh.

Zone được mô hình hoá bằng hai chiều độc lập: `kind` (`FIXED` | `FLEXIBLE`) × `admission_type` (`SEATED` | `STANDING`). Ba kiểu concert đều là tổ hợp của bốn ô này, không cần khái niệm mới. Một zone không thể vừa ngồi vừa đứng; muốn vậy thì chủ địa điểm chia thành hai zone.

Một lần giữ chỗ chứa được cả vé ngồi lẫn vé đứng. Trần: **vé ngồi ≤ 8, vé đứng ≤ 10, tổng ≤ 10.** Vé đứng nới hơn vì nhóm đi vé đứng thường đông hơn và việc tách đơn kéo theo hai lần chuyển khoản riêng — ma sát đủ để mất khách.

## Consequences

Tích cực:
- **Chốt chặn oversell y hệt cho cả hai loại vé**: unique index trên `seat_hold_items (session_seat_id) WHERE status = 'ACTIVE'`. Chỉ khác cách *chọn* đơn vị, không khác cách *bảo vệ*. Không có đường code thứ hai cho bất biến quan trọng nhất hệ thống.
- Ordering, Payment, Ledger, Ticketing **không phân biệt** hai loại vé — chúng chỉ thấy `order_items` trỏ tới `session_seats`.
- Toàn bộ bộ test đồng thời và load test đã có vẫn áp dụng được nguyên vẹn.

Tiêu cực và phải chấp nhận:
- Tốn hàng: một zone 3.000 vé đứng sinh 3.000 hàng. Không đáng kể so với quy mô bảng.
- `GET /sessions/{id}/seats` **không được** trả các đơn vị ảo xuống client — phải trả một khối tóm tắt tồn kho theo zone. Nếu quên, payload phình vô ích.
- Rủi ro hiệu năng của `ORDER BY id` + `SKIP LOCKED`: mọi request quét từ cùng một đầu. Đánh giá: số hàng bị khoá đồng thời bị chặn bởi kích thước connection pool (30–50), không phải bởi số người dùng online, nên mỗi truy vấn bỏ qua nhiều nhất vài trăm hàng. Nếu load test chứng minh ngược lại, phương án dự phòng là cột `alloc_bucket` ngẫu nhiên — chỉ làm khi có số đo.

## Validation

- Test đồng thời: 500 luồng cùng mua vé đứng ở zone 200 chỗ → đúng 200 vé phát hành, không hơn một vé.
- Test hỗn hợp: một lần giữ chỗ gồm 2 vé ngồi + 2 vé đứng → nguyên tử, hỏng một phần thì rollback cả bốn.
- Test: `GET /sessions/{id}/seats` của sự kiện có 3.000 vé đứng không trả 3.000 phần tử.
- Test: vé đứng check-in đúng như vé ngồi; rescan trả `ALREADY_CHECKED_IN`.
- Load test: kịch bản concert hỗn hợp 10k đồng thời, đo p95 của cả hai đường cấp phát.
