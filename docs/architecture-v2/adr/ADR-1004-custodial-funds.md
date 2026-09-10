# ADR-1004: NexaTicket giữ tiền

**Status:** Accepted — **thay thế ADR-0013** và `00-discovery/legal-constraints-vn.md §3`

## Context

ADR-0013 và tài liệu pháp lý v1 thiết kế có chủ đích để tiền khách đi thẳng vào tài khoản ngân hàng của organizer, nhằm tránh vùng cần giấy phép trung gian thanh toán. Chủ dự án yêu cầu đổi: NexaTicket là bên giữ tiền.

## Decision

Khách trả tiền qua **payOS**, vào tài khoản ảo payOS cấp cho từng link thanh toán và đối soát về **tài khoản ký quỹ của NexaTicket** (ADR-0016). payOS xác nhận bằng webhook đã ký. Ledger ghi nhận công nợ phải trả cho tổ chức và tách hoa hồng ngay tại thời điểm ghi nhận.

Tiền bị giữ tới sau khi sự kiện kết thúc cộng kỳ giữ tiền (mặc định 3 ngày làm việc), trừ dự phòng hoàn tiền (mặc định 5%), rồi mới chuyển sang số dư khả dụng để tổ chức yêu cầu chi trả.

`bank_accounts` của tổ chức đổi ý nghĩa: từ "nơi nhận tiền khách" thành "đích chi trả", chuyển quyền sở hữu sang payout-service.

## Consequences

Tích cực: thu hoa hồng chắc chắn; kiểm soát được hoàn tiền; chặn được kịch bản tổ chức bán vé rồi biến mất.

Tiêu cực: rủi ro gian lận và nghĩa vụ hoàn tiền chuyển từ organizer sang nền tảng; cần sổ cái kép, KYC, đối soát ngân hàng hằng ngày, quy trình chi trả có phê duyệt; và cần cơ sở pháp lý.

## Pháp lý

Giữ tiền của bên thứ ba ở Việt Nam thuộc phạm vi hoạt động trung gian thanh toán, cần giấy phép Ngân hàng Nhà nước (hiện hành: Nghị định 52/2024/NĐ-CP). Ba phương án ở [custodial-funds.md §9](../custodial-funds.md#9-pháp-lý--bắt-buộc-đọc); khuyến nghị hợp tác với đơn vị đã được cấp phép.

Thiết kế phần mềm không phụ thuộc vào phương án được chọn — chỉ thay đổi ai đứng tên tài khoản ký quỹ và ai thực hiện lệnh chuyển tiền cuối cùng. Do đó phát triển và xử lý pháp lý chạy song song được.

**Không được vận hành thật khi chưa có cơ sở pháp lý.**

## Validation

Diễn tập: huỷ một sự kiện đã bán 500 vé, hoàn tiền toàn bộ, kiểm tra sổ cái vẫn cân và tổng nghĩa vụ không vượt tiền thực có.
