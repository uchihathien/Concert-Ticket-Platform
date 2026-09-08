# ADR-1013: Trùng lịch địa điểm — cảnh báo, không chặn

**Status:** Accepted

## Context

Địa điểm dùng chung (ADR-1011) mở ra một vấn đề v1 không có: hai tổ chức có thể tạo sự kiện cùng địa điểm, cùng khung giờ.

Ràng buộc quyết định: **NexaTicket không quản lý việc thuê địa điểm.** Việc thuê diễn ra ngoài hệ thống, giữa tổ chức và chủ địa điểm. Hệ thống **không biết** tổ chức nào đã thuê, nên không có cơ sở để phán xử ai đúng ai sai.

Chặn cứng dựa trên dữ liệu mình không nắm sẽ chặn nhầm những trường hợp hợp lệ: hai sự kiện ở hai sảnh khác nhau khai cùng địa điểm, sự kiện buổi sáng và buổi tối cùng ngày, hoặc đơn giản là dữ liệu giờ giấc nhập chưa chuẩn.

## Decision

**Phát hiện và cảnh báo, không chặn.**

Khoảng chiếm dụng tính cả thời gian dựng và tháo: `[starts_at − setup_minutes, ends_at + teardown_minutes]`, hai tham số cấu hình trên từng địa điểm. Hai suất giao nhau trên cùng `venue_id` có `scope = PLATFORM` thì ghi nhận xung đột. Địa điểm riêng của tổ chức không kiểm tra vì chỉ một tổ chức dùng.

Kết quả trả về trong `warnings[]` của response, **không phải lỗi** — thao tác vẫn thành công.

### Ai được biết gì

Đây là phần quan trọng nhất của quyết định. Tiết lộ rằng tổ chức B có sự kiện **chưa công bố** tại địa điểm đó vào ngày đó là rò rỉ thông tin cạnh tranh — một tổ chức có thể dò lịch của đối thủ bằng cách thử tạo sự kiện.

| Suất diễn kia đang | Tổ chức đang thao tác thấy | Superadmin thấy |
| --- | --- | --- |
| `PUBLISHED` | Tên sự kiện + tên tổ chức + khung giờ (đã là thông tin công khai) | Đầy đủ |
| Chưa công bố | Chỉ câu chung: *"Địa điểm này đã có lịch khác trong khung giờ. Vui lòng xác nhận với chủ địa điểm."* | Đầy đủ |

Thông báo: tổ chức đang thao tác nhận cảnh báo inline ngay; superadmin nhận vào hàng đợi `/v1/platform/venue-conflicts` mọi lần; tổ chức của suất diễn có trước chỉ nhận email khi **cả hai** đã `PUBLISHED`.

### Truy vấn xuyên tenant có kiểm soát

Việc phát hiện bắt buộc phải đọc dữ liệu của tổ chức khác — một ngoại lệ với ADR-0008. Bốn ràng buộc:

1. Một truy vấn duy nhất, đánh dấu `@CrossTenantQuery(reason = "venue schedule conflict")`, không đi qua Hibernate tenant filter.
2. Chỉ trả về `sessionId`, `starts_at`, `ends_at`, `status`. Tên sự kiện và tên tổ chức chỉ ghép vào **sau khi** kiểm tra `status = PUBLISHED`.
3. Ghi audit mỗi lần chạy.
4. Integration test khẳng định không trường nào khác rò ra khi suất diễn kia chưa công bố.

## Consequences

Tích cực: tổ chức biết sớm để đi xác nhận với chủ địa điểm; superadmin có hàng đợi để làm trọng tài; không chặn nhầm trường hợp hợp lệ.

Tiêu cực và phải chấp nhận:
- Cảnh báo có thể bị bỏ qua — hệ thống không ngăn được hai sự kiện thật sự đụng nhau. Đây là hệ quả trực tiếp của việc không nắm dữ liệu thuê địa điểm, và là đánh đổi có ý thức.
- Mở một ngoại lệ xuyên tenant, dù rất hẹp. Phải review kỹ và có test riêng.
- Cần một màn hình mới cho superadmin (`P-CONFLICTS`) và một quy trình vận hành đi kèm.

**Không thuộc phạm vi:** đặt/giữ chỗ địa điểm, hợp đồng thuê, lịch khả dụng. Nếu sau này nền tảng thật sự nắm việc cho thuê, thêm `venue_bookings` do superadmin quản lý và nâng cảnh báo thành chặn cứng — lúc đó dữ liệu mới đủ để phán xử.

## Validation

- Test: hai suất trùng giờ cùng địa điểm dùng chung → sinh `venue_schedule_conflicts`, response có `warnings[]`, thao tác vẫn `201`.
- Test rò rỉ: suất diễn kia ở `DRAFT` → response **không** chứa tên sự kiện, tên tổ chức, hay id của tổ chức kia.
- Test: hai suất trùng giờ trên địa điểm riêng của cùng một tổ chức → không cảnh báo.
- Test: đệm dựng/tháo được tính đúng — hai suất cách nhau 2 giờ với `setup_minutes = 240` vẫn bị coi là trùng.
