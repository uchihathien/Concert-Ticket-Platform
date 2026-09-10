# ADR-0016: payOS thay SePay cho luồng thu tiền

**Status:** Accepted
**Supersedes:** [ADR-0013](./ADR-0013-vietqr-sepay-bank-transfer.md)

## Context

ADR-0013 dựng luồng thu tiền quanh hai thứ: nền tảng **tự sinh** chuỗi EMVCo VietQR trỏ về một tài khoản ký quỹ cố định, và **SePay** đọc sao kê rồi gửi về nguyên văn nội dung chuyển khoản. Đối soát là việc *rút mã đơn ra khỏi một câu chữ do khách gõ*.

Ba hệ quả của thiết kế đó, xếp theo mức độ tốn kém:

1. **Khách gõ sai nội dung thì tiền mồ côi.** Tiền vào tài khoản ký quỹ, không khớp đơn nào, và cách duy nhất để sửa là một con người dò `bank_webhook_log`. Nhiều người quét mã ở app này rồi chuyển tiền ở app khác, hoặc chuyển tại quầy — lúc đó nội dung là thứ họ gõ tay. Đây là trường hợp tệ nhất của cả luồng và nó xảy ra thường xuyên.
2. **Một tài khoản nhận tiền cho mọi đơn.** Không có cách nào phân biệt hai khoản tiền cùng số tiền vào cùng lúc ngoài nội dung chuyển khoản.
3. **Xác thực webhook bằng một khoá API tĩnh**, và cấu hình cho phép để trống khoá — nghĩa là "không kiểm". Một endpoint không xác thực mà đánh dấu đơn đã trả tiền là một máy phát vé miễn phí.

## Decision

Chuyển sang **payOS** làm cổng thanh toán. payOS cấp một **tài khoản ảo riêng cho từng link thanh toán** và tự dựng mã QR, nên khoá đối soát không còn là chuỗi khách gõ.

- `payment-service` gọi `POST /v2/payment-requests` để mở link, gửi kèm `orderCode` **do ta cấp** từ sequence `payment_order_code_seq`. Lưu lại `qrCode`, `checkoutUrl`, `bin`, `accountNumber`, `accountName`, `paymentLinkId` theo từng intent.
- `orderCode` là **khoá đối soát**. Webhook payOS trả đúng số đó về, trong payload đã ký HMAC-SHA256 bằng *checksum key*. Không còn bước rút mã khỏi nội dung chuyển khoản, nên không còn nhánh "gõ thiếu một ký tự".
- `payment_reference` (dạng `NT` + 7 chữ số) **suy ra từ `orderCode`** và đổi vai: từ khoá đối soát thành mô tả cho người đọc, để màn hình CSKH, sao kê ngân hàng và log payOS cùng hiện một con số. Giới hạn 9 ký tự là của payOS (`description` với tài khoản chưa liên kết).
- **Chữ ký HMAC-SHA256 là lớp xác thực duy nhất** của webhook, và nó **bắt buộc**: thiếu checksum key thì service từ chối mọi webhook, không "bỏ qua kiểm" như trước.
- Frontend đảo thứ tự hai đường trả tiền: `checkoutUrl` thành đường chính, quét QR thành đường phụ gập lại.
- Thêm `POST /internal/payment-intents/{orderId}/reconcile` để **kéo** trạng thái từ payOS về, thay vì chỉ chờ webhook **đẩy** sang.

## Consequences

**payOS không có môi trường sandbox.** Tài liệu của họ nói thẳng: mọi thử nghiệm diễn ra trên production, bằng tài khoản ngân hàng thật, với số tiền nhỏ. Hai hệ quả:

- `nexaticket.payment.sandbox` + `simulate-transfer` **cần thiết hơn trước**, không phải ít hơn. Nó là đường duy nhất chạy trọn luồng mua vé khi phát triển mà không tốn tiền. Nó đi qua đúng cùng `ConfirmTransferHandler` với webhook thật — cùng phép đối chiếu số tiền, cùng nhật ký, cùng lời gọi sang Ordering — nên nó không phát vé theo luật khác. Vẫn **phải** `false` ở production.
- Webhook payOS đòi URL **HTTPS gọi được từ internet**, nên ở máy phát triển nó không tới. Đó là lý do có `reconcile`. Ở production nó vẫn cần, vì lý do khác: một lần deploy đúng lúc tiền vào, hoặc payOS hết lượt retry, đều để lại một đơn đã trả tiền mà hệ thống không biết — trước đây phải sửa bằng SQL tay.

**Chữ ký sai trả 401, không 200.** Khác mọi nhánh từ chối khác (đều 200). Lý do cụ thể: trả 2xx cho một chữ ký sai nghĩa là hôm nào checksum key bị cấu hình lệch, payOS thôi giao lại và *mọi khoản tiền vào đều im lặng biến mất*. Một lỗi cấu hình phải kêu to và phải được retry.

**Huỷ đơn phải đóng cả link ở phía payOS.** Mỗi link có tài khoản ảo sống riêng; huỷ intent ở database mà để link sống nghĩa là khách mở lại tab cũ, chuyển tiền, và tiền vào một đơn không còn ghế. Bước đó là best-effort (không làm hỏng bù trừ của saga) và link vẫn tự hết hạn theo `expiredAt` = hạn thanh toán của đơn.

**Trần 9.999.999 link thanh toán.** Đến từ giới hạn 9 ký tự của `description`. Chạm trần thì `PaymentReference.forOrderCode` ném lỗi chứ không lặng lẽ sinh chuỗi 10 ký tự — một `description` quá dài làm payOS từ chối *mọi* lần tạo link sau đó.

**Vẫn ngoài phạm vi PCI-DSS.** Service không chạm dữ liệu thẻ; payOS xử lý phần đó trên trang của họ. Thêm một cổng thẻ vào hệ thống là quyết định làm thay đổi điều này và phải được cân nhắc tường minh.

**Các intent thời SePay được đánh `EXPIRED` trong migration V0101.** Chúng không bao giờ xác nhận được nữa vì endpoint webhook SePay đã không còn. Để nguyên `PENDING` nghĩa là mã QR cũ vẫn quét ra được và tiền vào mà không gì ghi nhận. Tiền đã vào (`CONFIRMED`) không bị đụng tới.

## Validation

- Vector vàng cho chữ ký HMAC (`PayosSignatureTest`), tính độc lập bằng `hmac`/`sha256` theo bản tham chiếu của payOS — không bằng chính lớp đang kiểm.
- Bất biến domain: số tiền trên link phải bằng số tiền đơn, reference phải thuộc `orderCode` của link, trả thiếu thì không phát vé, trả thừa thì vẫn ghi nhận (`PaymentIntentTest`).
- Idempotency của `openIntent`: gọi lại trả về đúng link cũ, **không** gọi payOS lần hai (`CheckoutSagaIT`).
- `UPDATE ... WHERE status = 'PENDING'` chặn job quét hết hạn ghi đè một xác nhận tiền vừa tới.
