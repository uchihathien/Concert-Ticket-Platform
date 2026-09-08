# ADR-1011: Địa điểm dùng chung với khu vực cố định và khu vực linh hoạt

**Status:** Accepted — thay thế mô hình `venue_seat_maps` / `seat_map_seats` trong [data-model.md v1](../../01-foundation/data-model.md)

## Context

Yêu cầu: một địa điểm được nhiều tổ chức dùng chung; mỗi tổ chức **tự thiết kế khu vực ghế cho từng sự kiện**; nhưng có những **khu vực ghế áp cứng không thay đổi được**.

Mô hình v1 có một khái niệm duy nhất là `seat_map` thuộc sở hữu tổ chức. Nó không diễn đạt được hai sự thật của một địa điểm thật:

1. Khán đài có ghế bắt vít vào bậc bê tông — cùng một sơ đồ cho mọi sự kiện, ban tổ chức không có quyền thay đổi.
2. Sân trung tâm trống — hôm nay là sân bóng rổ, mai kê 2.000 ghế cho concert. Mỗi sự kiện một cách bố trí.

Nó cũng không cho hai tổ chức dùng chung một địa điểm mà không sao chép dữ liệu.

## Decision

Tách khái niệm `seat_map` thành hai tầng có chủ sở hữu khác nhau:

**Tầng địa điểm — superadmin sở hữu.** `Venue` → `VenueLayoutVersion` → `VenueZone[]`. Mỗi zone có `kind`:
- `FIXED`: chứa `VenueFixedSeat[]` do superadmin định nghĩa. Tổ chức **không thêm, không xoá, không đổi tên** được.
- `FLEXIBLE`: không có ghế sẵn, chỉ có ranh giới và `max_capacity`.

**Tầng sự kiện — tổ chức sở hữu.** `EventSeatingPlan` gắn với một suất diễn, ghim một `venueLayoutVersionId`, gồm: chọn dùng zone nào, thiết kế khối ghế trong zone `FLEXIBLE`, gán hạng vé, và ghi đè từng ghế (`REMOVE` / `BLOCK` / `SET_TIER`).

Ranh giới quyền: **kết cấu vật lý thuộc chủ địa điểm; quyết định thương mại thuộc ban tổ chức.** Tổ chức không sửa được định nghĩa ghế cố định, nhưng toàn quyền quyết định có bán ghế đó không và bán giá nào.

Ghim `layoutVersionId` để việc cải tạo địa điểm không làm hỏng sự kiện đã bán vé — cùng nguyên tắc snapshot mà v1 dùng cho giá và tài khoản ngân hàng.

Khoá định danh ghế đổi từ `seat_map_seat_id` sang **`seat_code`** (`{zone}-{section}-{row}-{seat}`), vì ghế nay đến từ hai nguồn khác nhau. Bất biến chống oversell giữ nguyên hình dạng: `UNIQUE (event_session_id, seat_code)`.

Chi tiết lược đồ, materialize, API và mã lỗi: [venue-seating-model.md](../venue-seating-model.md).

## Consequences

Tích cực:
- Một địa điểm khai báo một lần, mọi tổ chức dùng lại — không phải vẽ lại 3.000 ghế mỗi lần.
- Ghế áp cứng được bảo vệ ở tầng mô hình, không phải bằng quy ước hay review.
- Sơ đồ sân linh hoạt theo từng sự kiện, đúng cách nhà thi đấu thật vận hành.
- Mẫu thiết kế (`seating_plan_templates`) cho phép tái sử dụng cấu hình quen thuộc.

Tiêu cực và phải chấp nhận:
- Catalog phức tạp hơn hẳn: thêm 7 bảng và một bước materialize có nhiều nguồn.
- Superadmin phải nhập ghế cố định cho mỗi địa điểm dùng chung — công việc thật, tốn giờ, cần công cụ grid + CSV tử tế.
- Sửa sơ đồ sau khi bán vé phải chặn theo từng thao tác (`FROZEN`), không thể cho sửa tự do.
- Địa điểm dùng chung mở ra vấn đề **trùng lịch giữa các tổ chức** mà v1 không có. MVP: cảnh báo mềm khi hai suất diễn đã publish trùng khung giờ tại cùng địa điểm; không chặn cứng, vì việc thuê địa điểm diễn ra ngoài hệ thống.

Phạm vi ảnh hưởng: **chỉ Catalog**. Inventory, Ordering, Payment, Ledger, Payout, Ticketing không đổi một dòng — mọi độ phức tạp dừng lại ở bước materialize `session_seats`. Đây là bằng chứng ranh giới bounded context đặt đúng chỗ.

## Validation

- Test: tổ chức gọi API sửa ghế trong zone `FIXED` → `FIXED_ZONE_NOT_EDITABLE`.
- Test: thiết kế vượt `max_capacity` → `ZONE_CAPACITY_EXCEEDED`.
- Test: hai khối sinh ra trùng `seat_code` → `DUPLICATE_SEAT_CODE` ở bước validate, không phải lúc materialize.
- Test: sau khi bán 1 vé, thêm khối ghế mới thành công; bỏ ghế đã bán → `SEAT_ALREADY_SOLD`; đổi layout version → `PLAN_FROZEN`.
- Test: superadmin tạo layout version mới sau khi sự kiện A đã publish; sự kiện A vẫn giữ nguyên sơ đồ cũ.
- Test tải: materialize 5.000 ghế (2 zone cố định + 3 khối linh hoạt) dưới 5 giây.
