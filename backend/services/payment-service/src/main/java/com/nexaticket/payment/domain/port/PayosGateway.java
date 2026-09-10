// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.port;

import com.nexaticket.payment.domain.model.PayosLink;
import java.time.Instant;
import java.util.Optional;

/**
 * Cổng sang payOS (ADR-0016).
 *
 * <p>Là một <b>port</b> ở domain nên nó nói bằng từ vựng của ta, không của họ: không có {@code code},
 * {@code desc}, {@code signature} nào lọt vào đây. Mọi chi tiết JSON và chữ ký nằm trong adapter. Đổi
 * nhà cung cấp lần nữa thì chỉ adapter phải viết lại.
 *
 * <p>Hai loại lỗi, và phân biệt được chúng là điều kiện để saga checkout xử lý đúng:
 *
 * <ul>
 *   <li>{@link Unavailable} — không nói chuyện được với payOS (mạng, timeout, 5xx). <b>Thử lại được</b>.
 *   <li>{@link Rejected} — payOS trả lời, và câu trả lời là "không". Thử lại sẽ nhận đúng câu đó.
 * </ul>
 */
public interface PayosGateway {

    /**
     * Tạo link thanh toán.
     *
     * <p><b>Không idempotent theo orderCode.</b> payOS từ chối một orderCode đã dùng, nên người gọi
     * phải cấp orderCode mới cho mỗi lần tạo và tự lo phần "đơn này đã có link chưa".
     *
     * @throws Unavailable không gọi được payOS
     * @throws Rejected payOS từ chối tạo link
     */
    PayosLink createPaymentLink(NewLink request);

    /**
     * Hỏi payOS xem một link đã thu được bao nhiêu tiền.
     *
     * <p>Đây là <b>lưới an toàn cho webhook</b>, và nó không phải thứ xa xỉ: webhook payOS cần một URL
     * HTTPS gọi được từ internet, nên ở máy phát triển nó không bao giờ tới, và ở production nó vẫn
     * trượt khi ta vừa deploy đúng lúc tiền vào. Không có đường kéo trạng thái về thì những đơn đó
     * phải xử lý tay.
     *
     * @return rỗng nếu payOS không biết orderCode này
     * @throws Unavailable không gọi được payOS
     */
    Optional<Settlement> fetchSettlement(long orderCode);

    /**
     * Đóng một link để nó không nhận tiền nữa.
     *
     * <p>Gọi khi saga checkout bù trừ. Bỏ bước này nghĩa là một đơn đã huỷ vẫn để lại một link sống:
     * khách mở lại tab cũ, chuyển tiền, và tiền vào một đơn không còn ghế — đúng trường hợp tệ nhất
     * vì nó phải hoàn bằng tay.
     *
     * @throws Unavailable không gọi được payOS
     * @throws Rejected payOS không cho huỷ (thường vì link đã nhận tiền)
     */
    void cancelPaymentLink(long orderCode, String reason);

    /**
     * Đăng ký URL webhook với payOS.
     *
     * <p>Tách thành một lệnh gọi tường minh chứ <b>không</b> chạy lúc khởi động: nó ghi cấu hình ở
     * phía nhà cung cấp, và một service tự làm việc đó mỗi lần khởi động sẽ có ngày trỏ webhook của
     * production về một tunnel trên máy ai đó.
     *
     * @throws Rejected payOS không nhận URL (không phải HTTPS, không gọi được, trả khác 2xx)
     */
    WebhookRegistration confirmWebhook(String webhookUrl);

    /**
     * Kiểm và đọc một webhook payOS gửi về.
     *
     * <p><b>Nhận chuỗi thô, không nhận object đã map.</b> Chữ ký tính trên đúng những byte payOS gửi; một
     * vòng deserialize rồi serialize lại có thể đổi cách số và chuỗi được viết ra, và chữ ký sẽ lệch vì
     * một lý do không ai nghĩ tới. Chuỗi thô cũng là thứ phải vào nhật ký nguyên văn.
     *
     * <p>Ở port chứ không ở controller, dù nó trông như việc của tầng web: hàm này biết payOS ký trường
     * nào và ký thế nào, tức là nó là kiến thức về một nhà cung cấp cụ thể. Để nó ở controller thì tầng
     * {@code interfaces} phải biết về HMAC và về hình dạng JSON của họ.
     *
     * <p>Không ném khi chữ ký sai: một chữ ký sai là <b>dữ liệu</b>, không phải sự cố của hệ thống. Người
     * gọi cần ghi nó vào nhật ký kèm lý do rồi trả lời payOS, chứ không cần một stack trace.
     */
    WebhookVerification verifyWebhook(String rawJsonBody);

    /**
     * @param orderCode mã số nguyên ta cấp; webhook sẽ trả đúng số này về
     * @param description nội dung chuyển khoản, <b>tối đa 9 ký tự</b> (xem
     *     {@link com.nexaticket.payment.domain.model.PaymentReference})
     * @param expiresAt hạn của link; nên trùng hạn thanh toán của đơn để payOS tự đóng link cùng lúc
     *     ghế được nhả
     */
    record NewLink(
            long orderCode,
            long amountVnd,
            String description,
            Instant expiresAt,
            String returnUrl,
            String cancelUrl) {}

    /**
     * Trạng thái payOS đang giữ cho một link.
     *
     * @param status {@code PENDING}, {@code PROCESSING}, {@code PAID} hoặc {@code CANCELLED}
     * @param amountPaidVnd số tiền <b>đã vào</b>, không phải số tiền của đơn
     * @param transactionReference mã giao dịch ngân hàng của lần trả tiền; null khi chưa có giao dịch
     *     nào
     */
    record Settlement(String status, long amountPaidVnd, String transactionReference) {

        public boolean isPaid() {
            return "PAID".equals(status);
        }
    }

    /** @param accountNumber tài khoản payOS đã nối với kênh — kiểm bằng mắt rằng ta đăng ký đúng kênh */
    record WebhookRegistration(String webhookUrl, String accountNumber, String accountName) {}

    /**
     * Kết quả kiểm một webhook.
     *
     * <p>Hai trạng thái loại trừ nhau: hoặc có {@code payment} và webhook là thật, hoặc có
     * {@code rejectionReason} và nó không đáng tin. Gói cả lý do vào đây thay vì chỉ trả true/false vì lý
     * do đó phải đi vào {@code bank_webhook_log}: "chữ ký không khớp" và "thiếu trường data" dẫn tới hai
     * việc phải làm hoàn toàn khác nhau.
     */
    record WebhookVerification(WebhookPayment payment, String rejectionReason) {

        public static WebhookVerification authentic(WebhookPayment payment) {
            return new WebhookVerification(payment, null);
        }

        public static WebhookVerification rejected(String reason) {
            return new WebhookVerification(null, reason);
        }

        public boolean isAuthentic() {
            return payment != null;
        }
    }

    /**
     * Một lần payOS báo có, đã qua kiểm chữ ký.
     *
     * @param orderCode mã link — khoá để tìm ra đơn
     * @param amountVnd số tiền <b>đã trả</b>, không phải số tiền của đơn
     * @param transactionReference mã giao dịch ngân hàng; khoá chống ghi nhận trùng. Null khi payOS không
     *     gửi mã nào — khi đó không được bịa ra một mã thay thế.
     * @param successful payOS báo giao dịch thành công hay không. Webhook thất bại vẫn phải nhận 2xx,
     *     nhưng không có tiền nào để xác nhận.
     * @param providerCode mã kết quả phía payOS, để ghi vào nhật ký và tra tài liệu của họ
     */
    record WebhookPayment(
            long orderCode,
            long amountVnd,
            String transactionReference,
            boolean successful,
            String providerCode,
            String providerDesc) {

        /** Đủ trường để đem đi đối chiếu với một intent hay chưa. */
        public boolean isComplete() {
            return orderCode > 0 && amountVnd > 0 && transactionReference != null;
        }
    }

    /** Không nói chuyện được với payOS. Thử lại được. */
    class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** payOS trả lời "không". Thử lại sẽ nhận đúng câu đó, nên đừng thử lại.(ADR-0016) */
    class Rejected extends RuntimeException {

        private final String code;

        public Rejected(String code, String message) {
            super(message);
            this.code = code;
        }

        /** Mã lỗi phía payOS, để tra tài liệu của họ. */
        public String code() {
            return code;
        }
    }
}
