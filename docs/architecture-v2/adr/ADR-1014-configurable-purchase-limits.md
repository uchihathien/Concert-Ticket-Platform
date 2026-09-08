# ADR-1014: Trần mua vé cấu hình theo tổ chức

**Status:** Accepted

## Context

Đến bản 4.1 có hai loại trần, cả hai đều là hằng số cứng cấp nền tảng:

1. **Trần mỗi lần giữ chỗ** — ngồi ≤ 8, đứng ≤ 10, tổng ≤ 10.
2. **Trần mỗi tài khoản trên mỗi suất diễn** — chưa có.

Một con số cứng không phù hợp với mọi sự kiện. Tiệc cuối năm doanh nghiệp đặt bàn 10 người cần trần cao; concert hot của một nghệ sĩ đang nổi cần trần thấp để chống gom vé. Chỉ ban tổ chức mới biết sự kiện của mình thuộc loại nào.

Trần mỗi lần giữ chỗ cũng **không phải công cụ chống phe vé**: ai muốn gom 100 vé chỉ cần đặt 10 đơn liên tiếp. Công cụ đúng là trần cộng dồn theo tài khoản trên mỗi suất diễn.

## Decision

### 1. Ba tầng cấu hình

```
Trần nền tảng (SUPER_ADMIN)  →  Mặc định của tổ chức  →  Ghi đè theo suất diễn
        trần cứng                    ORG_ADMIN+              EVENT_MANAGER+
```

```sql
platform_purchase_limits (           -- một hàng duy nhất, SUPER_ADMIN
  id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  max_seated_per_hold      INT NOT NULL DEFAULT 8,
  max_standing_per_hold    INT NOT NULL DEFAULT 10,
  max_units_per_hold       INT NOT NULL DEFAULT 10,
  max_tickets_per_customer INT NOT NULL DEFAULT 10,
  updated_at TIMESTAMPTZ NOT NULL, updated_by UUID NOT NULL
);

organization_purchase_limits (       -- mặc định của tổ chức; NULL = kế thừa nền tảng
  organization_id UUID PRIMARY KEY,
  max_seated_per_hold INT, max_standing_per_hold INT,
  max_units_per_hold  INT, max_tickets_per_customer INT
);

ALTER TABLE event_sessions           -- ghi đè cho một suất; NULL = kế thừa tổ chức
  ADD COLUMN max_seated_per_hold      INT,
  ADD COLUMN max_standing_per_hold    INT,
  ADD COLUMN max_units_per_hold       INT,
  ADD COLUMN max_tickets_per_customer INT;
```

Giá trị hiệu lực: `coalesce(session.x, organization.x, platform.x)`.

**Kiểm tra trần cứng lúc ghi cấu hình, không phải lúc giữ chỗ.** Tổ chức đặt giá trị vượt trần nền tảng → `LIMIT_EXCEEDS_PLATFORM_CEILING` ngay khi lưu. Nhờ vậy đường giữ chỗ chỉ đọc một cột, không phải tính `min()` ở 10k đồng thời. Khi superadmin hạ trần nền tảng, giá trị đang lưu được kẹp lại bằng `LEAST()` tại bước materialize.

Giá trị hiệu lực được **sao vào `session_inventory` lúc materialize**. Inventory không bao giờ gọi Catalog khi giữ chỗ — event-carried state, đúng như ADR-1002.

### 2. Thực thi trần mỗi tài khoản — dữ liệu nằm ở đâu

Vấn đề thật của quyết định này: "một người đã có bao nhiêu vé cho suất này" nghe như phải cộng dữ liệu của ba service — giữ chỗ (Inventory), đơn hàng (Ordering), vé (Ticketing).

Không cần. Thêm một cột vào bảng Inventory đã sở hữu:

```sql
ALTER TABLE session_seats ADD COLUMN holder_user_id UUID;  -- NULL khi AVAILABLE/BLOCKED
CREATE INDEX idx_seat_holder ON session_seats (event_session_id, holder_user_id)
  WHERE holder_user_id IS NOT NULL;
```

`holder_user_id` được ghi khi chỗ chuyển sang `HELD`, giữ nguyên qua `RESERVED` và `SOLD`, và **xoá** khi quay về `AVAILABLE` (hết hạn giữ chỗ, đơn hết hạn, huỷ, hoàn tiền).

Kiểm tra trần thành một câu đếm có index, trong một service, một bảng:

```sql
SELECT COUNT(*) FROM session_seats
 WHERE event_session_id = :sid AND holder_user_id = :uid
   AND status IN ('HELD', 'RESERVED', 'SOLD');
```

Ngữ nghĩa rơi ra đúng như mong muốn: chỗ đang giữ, đang chờ thanh toán và đã mua đều tính; chỗ đã nhả, đơn hết hạn và vé đã hoàn tiền tự động không tính nữa.

### 3. Chống chạy đua

Một người mở hai tab hoặc bấm hai lần có thể qua được phép đếm ở cả hai request. Khoá theo cặp (người dùng, suất diễn) ngay đầu transaction giữ chỗ:

```sql
SELECT pg_advisory_xact_lock(hashtextextended(:userId || ':' || :sessionId, 0));
```

Khoá này **không** tạo điểm nghẽn: nó chỉ tuần tự hoá các request của **cùng một người trên cùng một suất** — vốn đã nên tuần tự. Hai người khác nhau không bao giờ đụng nhau, nên thông lượng ở 10k đồng thời không đổi.

### 4. Hiện phần còn lại cho khách

`GET /v1/sessions/{id}/seats` khi đã đăng nhập trả thêm:

```json
"purchaseAllowance": { "limit": 10, "used": 4, "remaining": 6 }
```

Để khách biết trước còn mua được mấy vé, thay vì chọn 8 chỗ rồi mới bị từ chối ở bước cuối.

## Consequences

Tích cực:
- Tổ chức tự điều chỉnh theo tính chất sự kiện; nền tảng vẫn giữ trần cứng để không ai mở toang.
- Có công cụ chống gom vé thật sự, không chỉ là trần mỗi lần giữ chỗ.
- Thực thi nằm gọn trong Inventory: một bảng, một index, một câu đếm. Không truy vấn xuyên service, không counter phân tán.
- `holder_user_id` còn hữu ích cho vận hành: tra được ai đang giữ một chỗ cụ thể.

Tiêu cực và phải chấp nhận:
- Thêm một cột phải giữ đồng bộ với `status`. Nếu quên xoá khi nhả chỗ, khách bị khoá oan. **Phải có test cho cả bốn đường nhả**: hết hạn giữ chỗ, đơn hết hạn, huỷ đơn, hoàn tiền.
- Trần theo **tài khoản**, nên người quyết tâm vẫn lách được bằng nhiều tài khoản. Trần này nâng chi phí gom vé chứ không triệt tiêu. Muốn chặt hơn cần xác minh số điện thoại hoặc đối chiếu tài khoản chuyển khoản — ngoài phạm vi hiện tại.
- Ba tầng cấu hình là ba chỗ để nhìn khi debug "sao khách này không mua được". Màn hình cấu hình phải hiện rõ **giá trị hiệu lực** và **nó kế thừa từ đâu**, không chỉ hiện ô nhập.

## Validation

- Test kế thừa: suất diễn `NULL` → lấy của tổ chức; tổ chức `NULL` → lấy của nền tảng.
- Test trần cứng: tổ chức đặt vượt trần nền tảng → `LIMIT_EXCEEDS_PLATFORM_CEILING` lúc lưu.
- Test chạy đua: 20 request song song cùng một người, trần 10 → tổng đúng 10 chỗ, không hơn.
- Test bốn đường nhả: hết hạn giữ chỗ / đơn hết hạn / huỷ đơn / hoàn tiền → `holder_user_id` về `NULL` và hạn mức được trả lại.
- Test: `purchaseAllowance` khớp với số đếm thật sau mỗi thao tác.
- Load test: xác nhận advisory lock không làm p95 giữ chỗ xấu đi ở 10k đồng thời.
