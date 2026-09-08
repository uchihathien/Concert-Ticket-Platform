# ADR-1008: Mã truy cập soát vé phạm vi hẹp

**Status:** Accepted

## Context

Yêu cầu: mỗi tổ chức tự thêm nhân viên soát vé. RBAC v1 đã có role `CHECKIN_STAFF` cấp qua lời mời email.

Thực tế vận hành ở nhà thi đấu khác với giả định đó: nhân sự soát vé thường là người thuê theo ca, có mặt trước giờ mở cửa vài chục phút, không có email công ty, và không nên giữ quyền truy cập sau sự kiện. Bắt họ tạo tài khoản OIDC vào tối diễn là kịch bản thất bại. Kết quả thực tế sẽ là cả tổ dùng chung một tài khoản — mất hoàn toàn khả năng truy vết.

## Decision

Giữ lời mời qua email làm đường mặc định cho nhân viên dài hạn.

Bổ sung **mã truy cập theo suất diễn**: `ORG_ADMIN` phát hành mã 8 ký tự kèm QR, giới hạn theo một `event_session`, có hạn dùng theo giờ sự kiện và giới hạn số thiết bị. Nhân viên nhập mã trên web-scanner và nhận token phạm vi hẹp chỉ làm được đúng một việc: check-in cho suất đó.

Token không gia hạn được, thu hồi được ngay lập tức, và mọi lần phát hành hay thu hồi đều ghi audit. Mỗi thiết bị nhận một token riêng để truy vết được ai đã quét vé nào.

## Consequences

Bề mặt tấn công mới: mã bị lộ cho phép check-in trái phép trong phạm vi một suất. Giảm thiểu bằng thời hạn ngắn, giới hạn số thiết bị, thu hồi tức thì và cảnh báo khi tỷ lệ từ chối tăng đột biến.

Đổi lại: không còn động cơ dùng chung tài khoản, quyền tự hết hạn sau sự kiện, và tổ chức tự chủ hoàn toàn trong việc thêm người ngay tại cửa.

## Validation

Test: token hết hạn bị từ chối; token của suất A không check-in được vé suất B; thu hồi có hiệu lực tức thì; mọi check-in truy được về thiết bị đã quét.
