# ADR-1007: Tự tạo tổ chức; KYC chặn ở cổng chi trả

**Status:** ~~Accepted~~ → **SUPERSEDED bởi [ADR-1010](ADR-1010-superadmin-tenancy-and-finance-visibility.md)**

> Chủ dự án yêu cầu superadmin tạo tổ chức thay vì tự phục vụ. Máy trạng thái KYC mô tả ở dưới
> bị gỡ bỏ; việc thẩm định chuyển thành thao tác của con người trước khi tạo tổ chức.
> Giữ tài liệu này để ghi lại lý do đã cân nhắc.

## Context

Yêu cầu mới: người dùng tự tạo nhiều tổ chức. Kết hợp với việc nền tảng giữ tiền, điều này mở ra rủi ro rõ ràng: ai đó tạo tổ chức, bán vé cho sự kiện không có thật, rút tiền, biến mất.

Đặt KYC ở đâu là câu hỏi quyết định. Bắt KYC trước khi cho tạo sự kiện thì onboarding chết. Cho rút tiền trước khi KYC thì nền tảng thành công cụ rửa tiền.

## Decision

Bất kỳ user đã xác thực nào cũng tạo được tổ chức và tự động thành `ORG_OWNER`. Không giới hạn số tổ chức mỗi người, đặt trần chống lạm dụng 10 tổ chức.

Tổ chức mới ở trạng thái `UNVERIFIED`: **được** tạo venue, sự kiện, publish, bán vé, soát vé; **không được** yêu cầu chi trả.

KYC là điều kiện của **cổng chi trả**, không phải cổng bán hàng. Kèm theo: tên chủ tài khoản nhận chi trả phải khớp tên pháp nhân hoặc cá nhân đã thẩm định — ép ở tầng hệ thống, không phải tầng quy trình.

Giữ nguyên ADR-0008 về tenant isolation: tenant context lấy từ membership đã xác thực, không tin `organization_id` do client gửi.

## Consequences

Onboarding không ma sát, doanh thu bắt đầu chạy sớm, trong khi tiền vẫn an toàn vì bị giữ tới sau ngày diễn và không rút được nếu chưa thẩm định. Cần hàng đợi duyệt KYC và người vận hành xử lý — chi phí vận hành mới không có ở v1.

Cần ngưỡng rà soát thủ công: tổ chức mới bán vượt 100 triệu trong 24 giờ đầu; đổi tài khoản nhận ngay trước khi rút; một người đại diện đứng tên nhiều tổ chức.

## Validation

Test: tổ chức `UNVERIFIED` publish và bán được; yêu cầu chi trả bị từ chối với mã `KYC_REQUIRED`. Test: tài khoản nhận đứng tên khác hồ sơ KYC bị từ chối.
