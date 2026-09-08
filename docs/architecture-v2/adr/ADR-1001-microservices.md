# ADR-1001: Microservices theo bounded context

**Status:** Accepted — **thay thế ADR-0001** (modular monolith)

## Context

ADR-0001 chọn modular monolith với lý do chính xác: seat, order và payment cần nhất quán giao dịch, và microservices thêm chi phí vận hành khi chưa có bằng chứng cần tách. Lập luận đó vẫn đúng về mặt kỹ thuật thuần tuý.

Yêu cầu mới thay đổi bối cảnh: nền tảng giữ tiền, nên xuất hiện một miền tài chính (sổ cái, chi trả, KYC) có yêu cầu về cô lập bảo mật, nhịp thay đổi và quyền truy cập dữ liệu khác hẳn phần bán vé. Đồng thời chủ dự án yêu cầu kiến trúc microservices.

## Decision

Chia hệ thống thành 11 service theo bounded context (xem [context-map.md](../context-map.md)): identity, catalog, inventory, realtime-gateway, ordering, payment, ledger, payout, ticketing, notification, analytics — đứng sau một API gateway.

Ranh giới lấy theo **ranh giới ngôn ngữ**, không theo bảng dữ liệu hay tầng kỹ thuật.

Bất biến "không oversell" **vẫn nằm gọn trong một service, một database, một transaction** (inventory-service). Đây là điều kiện bắt buộc của quyết định này: nếu một ranh giới nào đó cắt ngang bất biến giao dịch, ranh giới đó sai.

## Consequences

Tích cực: miền tài chính cô lập được về bảo mật và deploy; inventory scale độc lập; realtime-gateway scale theo số kết nối thay vì theo QPS; đội làm việc song song không giẫm chân.

Tiêu cực và phải chấp nhận: mất transaction xuyên context nên cần saga; mất JOIN xuyên context nên cần event-carried state; chi phí vận hành tăng đáng kể (11 pipeline, 11 bộ migration, tracing phân tán); thời gian tới MVP ước tăng từ 13 lên 26–34 tuần với quy mô đội hiện tại.

Chấp nhận lộ trình B trong [README](../README.md#4-cái-giá-phải-trả--cần-đọc-trước-khi-cam-kết-tiến-độ): triển khai 4 deployable trước, giữ đúng ranh giới context trong code, tách dần khi có bằng chứng.

## Validation

ArchUnit ép chiều phụ thuộc và cấm truy cập chéo context. Contract test giữa mọi cặp service gọi nhau. Test chaos: giết một service giữa saga, hệ thống không mất tiền và không kẹt ghế quá 15 phút.
