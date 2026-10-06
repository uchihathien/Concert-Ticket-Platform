// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.infrastructure.payos;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.payment.application.PaymentProperties;
import com.nexaticket.payment.domain.model.PayosLink;
import com.nexaticket.payment.domain.port.PayosGateway;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Anti-Corruption Layer sang payOS.
 *
 * <p>Đây là lớp <b>duy nhất</b> biết JSON của payOS trông như thế nào. Mọi chi tiết khó chịu của họ
 * được chặn ở đây, và ba cái đáng gọi tên:
 *
 * <ol>
 *   <li><b>Lỗi nghiệp vụ đi kèm HTTP 200.</b> payOS trả {@code {"code":"xx","desc":"..."}} với mã khác
 *       {@code "00"} mà vẫn 200. Chỉ xem status code là coi một lần từ chối thành một lần thành công,
 *       rồi đọc {@code data} null và ném NullPointerException ở chỗ chẳng liên quan.
 *   <li><b>Chữ ký response.</b> payOS ký cả {@code data} trả về. Không kiểm nghĩa là một kẻ đứng giữa
 *       đổi được {@code accountNumber} trong mã QR — tiền của mọi khách đi sang tài khoản của họ, và
 *       webhook không bao giờ tới nên ta chỉ biết khi khách gọi lên khiếu nại.
 *   <li><b>Phân biệt "hỏng" với "không".</b> Timeout thì saga checkout nên để khách thử lại; payOS từ
 *       chối thì thử lại vô nghĩa. Hai nhánh đi vào hai exception khác nhau của port.
 * </ol>
 */
@Component
public class PayosHttpGateway implements PayosGateway {

    private static final Logger log = LoggerFactory.getLogger(PayosHttpGateway.class);

    /** Mã "thành công" của payOS. Mọi mã khác là một lần từ chối, kể cả khi HTTP là 200. */
    private static final String CODE_OK = "00";

    /** Mã NỘI BỘ của ta, không của payOS: chữ ký trên response họ trả về không khớp. */
    private static final String SIGNATURE_MISMATCH = "SIGNATURE_MISMATCH";

    private final RestClient client;
    private final PaymentProperties.Payos config;
    private final ObjectMapper json;

    public PayosHttpGateway(PaymentProperties properties, ObjectMapper json, RestClient.Builder builder) {
        this.config = properties.payos();
        this.json = json;
        // Builder ĐƯỢC TIÊM, kể cả với một nhà cung cấp bên ngoài. Header thêm vào chỉ là
        // correlation id và traceparent — không mang gì của người dùng — còn cái được là độ trễ
        // của payOS hiện ra trong trace. Đó là số liệu hay bị tranh cãi nhất khi thanh toán chậm.
        this.client = builder.baseUrl(config.baseUrl())
                .requestFactory(requestFactory(config))
                .defaultHeader("x-client-id", nullToEmpty(config.clientId()))
                .defaultHeader("x-api-key", nullToEmpty(config.apiKey()))
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                // Mọi status code đều đi xuống phần xử lý của ta: RestClient mặc định ném
                // RestClientResponseException ở 4xx/5xx, và làm vậy thì mất body — mà body chính là
                // chỗ payOS nói lý do từ chối.
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();

        if (!config.configured()) {
            // KÊU TO chứ không ném: service phải khởi động được ở máy phát triển và trong test mà
            // không có credential thật. Nhưng đây là thứ làm mọi lần checkout chết với 503, nên nó
            // không được lẫn vào giữa log INFO.
            log.error("payOS CHƯA ĐƯỢC CẤU HÌNH (thiếu client-id / api-key / checksum-key). "
                    + "Mọi lần mở link thanh toán sẽ trả 503 PAYOS_UNAVAILABLE.");
        } else {
            log.info("payOS: gọi {} bằng client-id {}", config.baseUrl(), mask(config.clientId()));
        }
    }

    @Override
    public PayosLink createPaymentLink(NewLink request) {
        requireConfigured();

        String description = request.description();
        // Kiểm ở đây nữa, dù PaymentReference đã đảm bảo: nếu có ngày ai đó đổi cách sinh description
        // (thêm tên sự kiện chẳng hạn), payOS sẽ trả một lỗi chung chung và người sửa sẽ đi tìm ở
        // chỗ khác. Câu lỗi này nói thẳng vấn đề.
        if (description.length() > 9) {
            throw new Rejected(
                    "LOCAL_VALIDATION",
                    "description \"%s\" dài %d ký tự; payOS cho tối đa 9".formatted(description, description.length()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderCode", request.orderCode());
        body.put("amount", request.amountVnd());
        body.put("description", description);
        body.put("cancelUrl", request.cancelUrl());
        body.put("returnUrl", request.returnUrl());
        if (request.expiresAt() != null) {
            // Int32 Unix timestamp theo tài liệu payOS. Hạn của link nên trùng hạn thanh toán của đơn
            // để payOS tự đóng link đúng lúc ghế được nhả — nếu không, một link sống lâu hơn đơn là
            // một khoản tiền sẽ vào một đơn không còn ghế.
            body.put("expiredAt", request.expiresAt().getEpochSecond());
        }
        body.put(
                "signature",
                PayosSignature.signPaymentRequest(
                        request.amountVnd(),
                        request.cancelUrl(),
                        description,
                        request.orderCode(),
                        request.returnUrl(),
                        config.checksumKey()));

        JsonNode data = call("POST", "/v2/payment-requests", body, "tạo link thanh toán");

        PayosLink link = new PayosLink(
                data.path("orderCode").asLong(),
                text(data, "paymentLinkId"),
                text(data, "checkoutUrl"),
                text(data, "qrCode"),
                text(data, "bin"),
                text(data, "accountNumber"),
                text(data, "accountName"),
                data.path("amount").asLong(),
                text(data, "status"));

        log.info(
                "payOS đã mở link {} cho orderCode {} — tài khoản ảo {} tại BIN {}",
                link.paymentLinkId(),
                link.orderCode(),
                mask(link.accountNumber()),
                link.bin());
        return link;
    }

    @Override
    public Optional<Settlement> fetchSettlement(long orderCode) {
        requireConfigured();
        JsonNode data;
        try {
            data = call("GET", "/v2/payment-requests/" + orderCode, null, "tra trạng thái link");
        } catch (Rejected e) {
            // Chữ ký response không khớp thì NÉM TIẾP, không quy về "chưa có gì".
            //
            // Nuốt nó ở đây là nuốt đúng tín hiệu của một kẻ đứng giữa: lệnh đối soát sẽ báo "payOS không
            // biết link này", người vận hành đi tìm ở chỗ khác, và không ai biết có người đang sửa response
            // trên đường truyền. Một nhánh catch rộng quá ở chỗ này biến một báo động thành một dòng INFO.
            if (SIGNATURE_MISMATCH.equals(e.code())) {
                throw e;
            }
            // Mọi lý do từ chối còn lại đều là "payOS không có orderCode này", và với luồng đối soát đó là
            // "chưa có gì" chứ không phải lỗi: có thể link chưa kịp tạo xong, hoặc intent thuộc thời SePay
            // và không có link payOS nào.
            log.info("payOS không có orderCode {} ({}): coi như chưa có giao dịch", orderCode, e.code());
            return Optional.empty();
        }

        long amountPaid = data.path("amountPaid").asLong();
        // Mã giao dịch ngân hàng nằm trong phần tử MỚI NHẤT của transactions. Không có giao dịch nào
        // thì để null — đừng bịa ra paymentLinkId làm mã giao dịch, vì nó sẽ thành khoá chống trùng và
        // làm một lần chuyển tiền thứ hai bị coi là trùng lặp.
        String txnRef = null;
        JsonNode transactions = data.path("transactions");
        if (transactions.isArray() && !transactions.isEmpty()) {
            txnRef = text(transactions.get(transactions.size() - 1), "reference");
        }
        return Optional.of(new Settlement(text(data, "status"), amountPaid, txnRef));
    }

    @Override
    public void cancelPaymentLink(long orderCode, String reason) {
        requireConfigured();
        call(
                "POST",
                "/v2/payment-requests/" + orderCode + "/cancel",
                Map.of("cancellationReason", reason == null ? "Đơn hàng đã đóng" : reason),
                "huỷ link thanh toán");
        log.info("payOS đã đóng link của orderCode {}: {}", orderCode, reason);
    }

    @Override
    public WebhookRegistration confirmWebhook(String webhookUrl) {
        requireConfigured();
        JsonNode data = call("POST", "/confirm-webhook", Map.of("webhookUrl", webhookUrl), "đăng ký webhook");
        return new WebhookRegistration(
                text(data, "webhookUrl"), text(data, "accountNumber"), text(data, "accountName"));
    }

    /**
     * Kiểm chữ ký HMAC-SHA256 của một webhook rồi đọc nó ra.
     *
     * <p>Ba điểm là hợp đồng bảo mật, không phải chuyện định dạng:
     *
     * <ol>
     *   <li><b>Chữ ký tính trên trường {@code data}, không trên cả body.</b> Ký cả body thì
     *       {@code signature} sẽ phải ký chính nó. Nhầm chỗ này cho ra một hệ thống từ chối 100% webhook
     *       thật, và triệu chứng giống hệt sai checksum key.
     *   <li><b>Thiếu checksum key thì TỪ CHỐI</b>, không mở cửa. Đây là khác biệt cố ý so với webhook SePay
     *       trước đây, chỗ mà khoá rỗng nghĩa là "không kiểm": một endpoint không xác thực mà đánh dấu đơn
     *       đã trả tiền là một máy phát vé miễn phí, và "chỉ ở máy phát triển thôi" là câu người ta nói
     *       trước khi nó lên production.
     *   <li><b>Chỉ đọc các trường SAU khi chữ ký đã khớp.</b> Trước đó chúng là dữ liệu từ internet.
     * </ol>
     */
    @Override
    public WebhookVerification verifyWebhook(String rawJsonBody) {
        if (!config.configured()) {
            log.error("Thiếu nexaticket.payment.payos.checksum-key: TỪ CHỐI mọi webhook payOS");
            return WebhookVerification.rejected("Service chưa có checksum key");
        }

        JsonNode body;
        try {
            body = json.readTree(rawJsonBody == null ? "" : rawJsonBody);
        } catch (JsonProcessingException e) {
            log.warn(
                    "Webhook payOS có body không phải JSON, dài {} byte",
                    rawJsonBody == null ? 0 : rawJsonBody.length());
            return WebhookVerification.rejected("Body không phải JSON");
        }
        if (body == null || !body.isObject()) {
            return WebhookVerification.rejected("Body không phải object JSON");
        }

        JsonNode data = body.path("data");
        if (!data.isObject()) {
            log.warn("Webhook payOS không có trường data dạng object");
            return WebhookVerification.rejected("Thiếu trường data");
        }

        String expected = PayosSignature.sign(data, config.checksumKey());
        if (!PayosSignature.matches(expected, body.path("signature").asText(null))) {
            // ERROR, không WARN. Chỉ có hai khả năng và cả hai đều cần người xem ngay: ai đó đang giả
            // webhook, hoặc checksum key đang lệch và MỌI khoản tiền vào đều đang bị từ chối.
            log.error(
                    "CHỮ KÝ WEBHOOK payOS KHÔNG KHỚP (orderCode {}) — từ chối",
                    data.path("orderCode").asText("?"));
            return WebhookVerification.rejected("Chữ ký không khớp");
        }

        String code = body.path("code").asText("");
        // payOS nói thành công ở HAI chỗ: success/code ở envelope và code bên trong data. Đòi cả hai đều
        // đúng — chỉ trường nào nằm trong data mới thuộc phạm vi chữ ký, nên trường ngoài envelope một
        // mình thì không đủ tin.
        boolean successful = (body.path("success").asBoolean(false) || CODE_OK.equals(code))
                && CODE_OK.equals(data.path("code").asText(CODE_OK));

        return WebhookVerification.authentic(new WebhookPayment(
                data.path("orderCode").asLong(0),
                data.path("amount").asLong(0),
                transactionIdOf(data),
                successful,
                code,
                body.path("desc").asText("")));
    }

    /**
     * Ưu tiên mã tham chiếu của ngân hàng; không có thì dùng id của link payOS.
     *
     * <p>Giá trị này thành khoá chống ghi nhận trùng ({@code uq_provider_txn}), nên thứ tự ưu tiên có hệ
     * quả thật: {@code reference} là của từng lần chuyển tiền, còn {@code paymentLinkId} là của cả link.
     * Dùng {@code paymentLinkId} khi đã có {@code reference} sẽ khiến lần chuyển tiền thứ hai vào cùng link
     * bị coi là trùng lặp — và đó là đúng tình huống cần một con người xem.
     */
    private static String transactionIdOf(JsonNode data) {
        String reference = text(data, "reference");
        if (reference != null && !reference.isBlank()) {
            return reference;
        }
        String linkId = text(data, "paymentLinkId");
        return linkId == null || linkId.isBlank() ? null : linkId;
    }

    /**
     * Một lượt gọi payOS: gửi, đọc envelope, kiểm mã nghiệp vụ, kiểm chữ ký, trả về {@code data}.
     *
     * @return node {@code data}; không bao giờ null
     */
    private JsonNode call(String method, String path, Object body, String what) {
        JsonNode envelope;
        try {
            RestClient.RequestHeadersSpec<?> spec = "GET".equals(method)
                    ? client.get().uri(path)
                    : client.post().uri(path).body(body == null ? Map.of() : body);
            envelope = spec.retrieve().body(JsonNode.class);
        } catch (RuntimeException e) {
            throw new Unavailable("Không gọi được payOS để " + what, e);
        }
        if (envelope == null) {
            throw new Unavailable("payOS trả body rỗng khi " + what, null);
        }

        String code = envelope.path("code").asText("");
        String desc = envelope.path("desc").asText("");
        if (!CODE_OK.equals(code)) {
            throw new Rejected(code, "payOS từ chối %s: [%s] %s".formatted(what, code, desc));
        }

        JsonNode data = envelope.path("data");
        if (!data.isObject()) {
            throw new Rejected(code, "payOS trả code 00 nhưng không có data khi " + what);
        }

        verifyResponseSignature(envelope, data, what);
        return data;
    }

    /**
     * Kiểm chữ ký payOS ký trên {@code data} trả về.
     *
     * <p>Response <b>không có chữ ký</b> thì bỏ qua, không ném: {@code /confirm-webhook} không ký, và
     * payOS có thể thêm/bớt ở các endpoint khác. Nhưng có chữ ký mà <b>sai</b> thì dừng hẳn — đó là
     * dấu hiệu của một người đứng giữa, và ta sắp lưu {@code accountNumber} từ response đó vào mã QR
     * của khách.
     */
    private void verifyResponseSignature(JsonNode envelope, JsonNode data, String what) {
        String received = envelope.path("signature").asText(null);
        if (received == null || received.isBlank()) {
            return;
        }
        String expected = PayosSignature.sign(data, config.checksumKey());
        if (!PayosSignature.matches(expected, received)) {
            log.error("CHỮ KÝ RESPONSE payOS KHÔNG KHỚP khi {} — dừng lại, không dùng dữ liệu này", what);
            throw new Rejected(SIGNATURE_MISMATCH, "Chữ ký response payOS không khớp khi " + what);
        }
    }

    private void requireConfigured() {
        if (!config.configured()) {
            throw new Unavailable(
                    "Chưa cấu hình payOS: cần nexaticket.payment.payos.client-id, .api-key và .checksum-key", null);
        }
    }

    private static ClientHttpRequestFactory requestFactory(PaymentProperties.Payos config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.connectTimeout());
        factory.setReadTimeout(config.readTimeout());
        return factory;
    }

    /** @return null khi payOS không gửi trường đó, chứ không phải chuỗi {@code "null"} */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Số tài khoản và client id không được vào log nguyên văn. */
    private static String mask(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        return "****" + value.substring(value.length() - 4);
    }
}
