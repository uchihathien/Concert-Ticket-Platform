// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.support;

import com.nexaticket.payment.domain.model.PayosLink;
import com.nexaticket.payment.domain.port.OrderingPort;
import com.nexaticket.payment.domain.port.PayosGateway;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * payOS và Ordering giả, điều khiển được.
 *
 * <p>{@code @Primary} để đè bean thật: {@link com.nexaticket.payment.infrastructure.payos.PayosHttpGateway}
 * vẫn được tạo (nó là {@code @Component} và ta muốn constructor của nó vẫn phải chạy được), nhưng không
 * bean nào inject nó.
 *
 * <p><b>Chữ ký webhook vẫn kiểm THẬT.</b> Fake này chỉ đè phần gọi mạng; {@link #verifyWebhook} gọi đúng
 * {@code PayosSignature} như bản thật. Một fake tự nhận "chữ ký nào cũng hợp lệ" sẽ làm mọi test webhook
 * xanh, kể cả khi phép kiểm chữ ký bị hỏng hoàn toàn — tức là xanh với đúng cái lỗi tệ nhất có thể có.
 */
@TestConfiguration
public class FakePayos {

    @Bean
    @Primary
    public FakePayosGateway fakePayosGateway() {
        return new FakePayosGateway();
    }

    @Bean
    @Primary
    public FakeOrdering fakeOrdering() {
        return new FakeOrdering();
    }

    /** payOS giả. Tạo link trả về dữ liệu có hình dạng thật; trạng thái thu tiền do test đặt. */
    public static class FakePayosGateway implements PayosGateway {

        public final List<Long> created = new ArrayList<>();
        public final List<Long> cancelled = new ArrayList<>();
        public final Map<Long, Settlement> settlements = new HashMap<>();

        /** Bật để mô phỏng payOS không phản hồi. */
        public boolean unavailable;

        /** Bật để mô phỏng payOS từ chối tạo link. */
        public boolean rejectCreate;

        /** Ép {@code amount} trong response lệch số tiền yêu cầu, để thử bất biến ở domain. */
        public Long forcedAmountVnd;

        public void reset() {
            created.clear();
            cancelled.clear();
            settlements.clear();
            unavailable = false;
            rejectCreate = false;
            forcedAmountVnd = null;
        }

        @Override
        public PayosLink createPaymentLink(NewLink request) {
            if (unavailable) {
                throw new Unavailable("payOS không phản hồi (giả lập)", null);
            }
            if (rejectCreate) {
                throw new Rejected("231", "orderCode đã tồn tại (giả lập)");
            }
            created.add(request.orderCode());
            return new PayosLink(
                    request.orderCode(),
                    "link-" + request.orderCode(),
                    "https://pay.payos.vn/web/link-" + request.orderCode(),
                    // Chuỗi EMVCo đủ hình dạng để không ai nhầm nó với một placeholder.
                    "00020101021238570010A00000072701270006970422011" + request.orderCode() + "0208QRIBFTTA5303704",
                    "970422",
                    // Tài khoản ảo RIÊNG cho từng link, đúng như payOS cấp. Dùng chung một số ở fake sẽ
                    // che mất mọi lỗi gắn sai tài khoản vào đơn.
                    "V3CAS" + request.orderCode(),
                    "NEXATICKET",
                    forcedAmountVnd == null ? request.amountVnd() : forcedAmountVnd,
                    "PENDING");
        }

        @Override
        public Optional<Settlement> fetchSettlement(long orderCode) {
            if (unavailable) {
                throw new Unavailable("payOS không phản hồi (giả lập)", null);
            }
            return Optional.ofNullable(settlements.get(orderCode));
        }

        @Override
        public void cancelPaymentLink(long orderCode, String reason) {
            if (unavailable) {
                throw new Unavailable("payOS không phản hồi (giả lập)", null);
            }
            cancelled.add(orderCode);
        }

        @Override
        public WebhookRegistration confirmWebhook(String webhookUrl) {
            return new WebhookRegistration(webhookUrl, "V3CAS0000", "NEXATICKET");
        }

        /**
         * Kiểm chữ ký bằng chính {@code PayosSignature} của bản thật — xem javadoc của lớp ngoài.
         *
         * <p>Phần duy nhất không dùng lại được là đoạn đọc JSON, vì nó nằm trong adapter HTTP. Viết lại
         * ở đây tối thiểu và gọi đúng hàm ký thật.
         */
        @Override
        public WebhookVerification verifyWebhook(String rawJsonBody) {
            com.fasterxml.jackson.databind.JsonNode body;
            try {
                body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rawJsonBody);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return WebhookVerification.rejected("Body không phải JSON");
            }
            com.fasterxml.jackson.databind.JsonNode data = body.path("data");
            if (!data.isObject()) {
                return WebhookVerification.rejected("Thiếu trường data");
            }
            String expected =
                    com.nexaticket.payment.infrastructure.payos.PayosSignature.sign(data, PaymentTestBase.CHECKSUM_KEY);
            if (!com.nexaticket.payment.infrastructure.payos.PayosSignature.matches(
                    expected, body.path("signature").asText(null))) {
                return WebhookVerification.rejected("Chữ ký không khớp");
            }
            String code = body.path("code").asText("");
            boolean successful = (body.path("success").asBoolean(false) || "00".equals(code))
                    && "00".equals(data.path("code").asText("00"));
            String reference = data.path("reference").asText(null);
            return WebhookVerification.authentic(new WebhookPayment(
                    data.path("orderCode").asLong(0),
                    data.path("amount").asLong(0),
                    reference == null || reference.isBlank() ? null : reference,
                    successful,
                    code,
                    body.path("desc").asText("")));
        }
    }

    /** Ordering giả. {@code unavailable} để thử nhánh rollback khi không báo được tiền về. */
    public static class FakeOrdering implements OrderingPort {

        public final List<UUID> confirmed = new ArrayList<>();
        public boolean unavailable;

        /** Ép Ordering trả lời "đơn đã đóng, tiền vào muộn" — nhánh MANUAL_REVIEW. */
        public boolean orderAlreadyClosed;

        public void reset() {
            confirmed.clear();
            unavailable = false;
            orderAlreadyClosed = false;
        }

        @Override
        public Confirmation confirmPayment(UUID orderId) {
            if (unavailable) {
                throw new OrderingUnavailableException("Ordering không phản hồi (giả lập)", null);
            }
            confirmed.add(orderId);
            return orderAlreadyClosed ? Confirmation.MANUAL_REVIEW : Confirmation.PAID;
        }
    }
}
