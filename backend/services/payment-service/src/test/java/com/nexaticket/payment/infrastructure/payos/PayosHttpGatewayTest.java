// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.infrastructure.payos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexaticket.payment.application.PaymentProperties;
import com.nexaticket.payment.domain.model.PayosLink;
import com.nexaticket.payment.domain.port.PayosGateway;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Anti-Corruption Layer sang payOS — không gọi mạng thật.
 *
 * <p>payOS không có sandbox: một test gọi thật là tiền thật. Server giả là {@link MockRestServiceServer}.
 *
 * <p><b>Mẹo nối server giả:</b> adapter tự gọi {@code builder.requestFactory(...)} để đặt timeout, và lời
 * gọi đó sẽ đè lên request factory giả. Builder ở đây là một spy mà {@code requestFactory} bị vô hiệu
 * <i>sau khi</i> server giả đã cắm vào — phần còn lại của adapter (header, status handler, đọc body) là
 * code thật.
 */
class PayosHttpGatewayTest {

    private static final String BASE = "https://payos.test";
    private static final String KEY = "test-checksum-key";
    private static final ObjectMapper JSON = new ObjectMapper();

    private MockRestServiceServer server;
    private PayosHttpGateway gateway;

    private static PaymentProperties properties(String clientId, String apiKey, String checksumKey) {
        return new PaymentProperties(
                false, new PaymentProperties.Payos(BASE, clientId, apiKey, checksumKey, null, null, null, null));
    }

    private PayosHttpGateway gateway(PaymentProperties properties) {
        RestClient.Builder builder = spy(RestClient.builder());
        server = MockRestServiceServer.bindTo(builder).build();
        doReturn(builder).when(builder).requestFactory(any());
        return new PayosHttpGateway(properties, JSON, builder);
    }

    @BeforeEach
    void setUp() {
        gateway = gateway(properties("client-123", "api-456", KEY));
    }

    private static ObjectNode linkData(long orderCode, long amount) {
        ObjectNode data = JSON.createObjectNode();
        data.put("bin", "970422");
        data.put("accountNumber", "V3CAS" + orderCode);
        data.put("accountName", "NEXATICKET");
        data.put("amount", amount);
        data.put("description", "NT" + orderCode);
        data.put("orderCode", orderCode);
        data.put("currency", "VND");
        data.put("paymentLinkId", "link-" + orderCode);
        data.put("status", "PENDING");
        data.put("checkoutUrl", "https://pay.payos.vn/web/link-" + orderCode);
        data.put("qrCode", "000201010212QR" + orderCode);
        return data;
    }

    /** Envelope payOS đã ký bằng checksum key của test. */
    private static String signedEnvelope(JsonNode data) {
        return "{\"code\":\"00\",\"desc\":\"success\",\"data\":%s,\"signature\":\"%s\"}"
                .formatted(data, PayosSignature.sign(data, KEY));
    }

    private static PayosGateway.NewLink newLink(long orderCode, long amount, String description) {
        return new PayosGateway.NewLink(
                orderCode,
                amount,
                description,
                Instant.parse("2026-09-09T03:15:00Z"),
                "https://web/orders/x/pay",
                "https://web/orders/x/pay");
    }

    @Nested
    @DisplayName("Tạo link")
    class TaoLink {

        @Test
        @DisplayName("Gửi đúng header, đúng body và chữ ký năm trường; đọc lại link từ data đã ký")
        void tao_link_thanh_cong() {
            String expectedSignature = PayosSignature.signPaymentRequest(
                    3_000_000L, "https://web/orders/x/pay", "NT1000001", 1_000_001L, "https://web/orders/x/pay", KEY);
            server.expect(requestTo(BASE + "/v2/payment-requests"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("x-client-id", "client-123"))
                    .andExpect(header("x-api-key", "api-456"))
                    .andExpect(jsonPath("$.orderCode").value(1_000_001))
                    .andExpect(jsonPath("$.amount").value(3_000_000))
                    .andExpect(jsonPath("$.description").value("NT1000001"))
                    .andExpect(jsonPath("$.expiredAt")
                            .value(Instant.parse("2026-09-09T03:15:00Z").getEpochSecond()))
                    .andExpect(jsonPath("$.signature").value(expectedSignature))
                    // Checksum key KHÔNG BAO GIỜ rời khỏi service.
                    .andExpect(request ->
                            assertThat(request.getHeaders().toString()).doesNotContain(KEY))
                    .andRespond(
                            withSuccess(signedEnvelope(linkData(1_000_001L, 3_000_000L)), MediaType.APPLICATION_JSON));

            PayosLink link = gateway.createPaymentLink(newLink(1_000_001L, 3_000_000L, "NT1000001"));

            server.verify();
            assertThat(link.orderCode()).isEqualTo(1_000_001L);
            assertThat(link.amountVnd()).isEqualTo(3_000_000L);
            assertThat(link.accountNumber()).isEqualTo("V3CAS1000001");
            assertThat(link.paymentLinkId()).isEqualTo("link-1000001");
            assertThat(link.qrCode()).isEqualTo("000201010212QR1000001");
            assertThat(link.status()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("Không có hạn: KHÔNG gửi expiredAt")
        void khong_co_han() {
            server.expect(requestTo(BASE + "/v2/payment-requests"))
                    .andExpect(jsonPath("$.expiredAt").doesNotExist())
                    .andRespond(withSuccess(signedEnvelope(linkData(1_000_002L, 10_000L)), MediaType.APPLICATION_JSON));

            gateway.createPaymentLink(new PayosGateway.NewLink(1_000_002L, 10_000L, "NT1000002", null, "r", "c"));

            server.verify();
        }

        @Test
        @DisplayName("Mô tả dài hơn 9 ký tự: từ chối tại chỗ, KHÔNG gọi payOS")
        void mo_ta_qua_dai() {
            assertThatThrownBy(() -> gateway.createPaymentLink(newLink(1_000_001L, 1_000L, "NT10000012")))
                    .isInstanceOfSatisfying(PayosGateway.Rejected.class, e -> assertThat(e.code())
                            .isEqualTo("LOCAL_VALIDATION"));
            server.verify();
        }

        @Test
        @DisplayName("Chưa cấu hình credential: Unavailable, KHÔNG gọi payOS")
        void chua_cau_hinh() {
            PayosHttpGateway unconfigured = gateway(properties("client", "api", null));

            assertThatThrownBy(() -> unconfigured.createPaymentLink(newLink(1_000_001L, 1_000L, "NT1000001")))
                    .isInstanceOf(PayosGateway.Unavailable.class);
            server.verify();
        }

        @Test
        @DisplayName("payOS trả HTTP 200 kèm code khác 00: Rejected mang đúng mã payOS")
        void loi_nghiep_vu_trong_http_200() {
            server.expect(requestTo(BASE + "/v2/payment-requests"))
                    .andRespond(withSuccess(
                            "{\"code\":\"231\",\"desc\":\"Đơn thanh toán đã tồn tại\",\"data\":null}",
                            MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> gateway.createPaymentLink(newLink(1_000_001L, 1_000L, "NT1000001")))
                    .isInstanceOfSatisfying(PayosGateway.Rejected.class, e -> {
                        assertThat(e.code()).isEqualTo("231");
                        assertThat(e.getMessage()).contains("Đơn thanh toán đã tồn tại");
                    });
        }

        @Test
        @DisplayName("HTTP 400 kèm body lỗi: đọc được lý do (không mất body), Rejected")
        void http_400_doc_duoc_body() {
            server.expect(requestTo(BASE + "/v2/payment-requests"))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"code\":\"20\",\"desc\":\"signature không hợp lệ\"}"));

            assertThatThrownBy(() -> gateway.createPaymentLink(newLink(1_000_001L, 1_000L, "NT1000001")))
                    .isInstanceOfSatisfying(PayosGateway.Rejected.class, e -> {
                        assertThat(e.code()).isEqualTo("20");
                        assertThat(e.getMessage()).contains("signature không hợp lệ");
                    });
        }

        @Test
        @DisplayName("Code 00 mà không có data: Rejected, không NullPointerException")
        void code_00_khong_data() {
            server.expect(requestTo(BASE + "/v2/payment-requests"))
                    .andRespond(withSuccess("{\"code\":\"00\",\"desc\":\"success\"}", MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> gateway.createPaymentLink(newLink(1_000_001L, 1_000L, "NT1000001")))
                    .isInstanceOf(PayosGateway.Rejected.class)
                    .hasMessageContaining("không có data");
        }

        @Test
        @DisplayName("Chữ ký response bị sửa (đổi số tài khoản ảo): Rejected SIGNATURE_MISMATCH")
        void chu_ky_response_sai() {
            ObjectNode data = linkData(1_000_001L, 3_000_000L);
            String signature = PayosSignature.sign(data, KEY);
            data.put("accountNumber", "TAI-KHOAN-KE-GIAN");
            server.expect(requestTo(BASE + "/v2/payment-requests"))
                    .andRespond(withSuccess(
                            "{\"code\":\"00\",\"desc\":\"success\",\"data\":%s,\"signature\":\"%s\"}"
                                    .formatted(data, signature),
                            MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> gateway.createPaymentLink(newLink(1_000_001L, 3_000_000L, "NT1000001")))
                    .isInstanceOfSatisfying(PayosGateway.Rejected.class, e -> assertThat(e.code())
                            .isEqualTo("SIGNATURE_MISMATCH"));
        }

        @Test
        @DisplayName("Lỗi mạng: Unavailable — thử lại được")
        void loi_mang() {
            server.expect(requestTo(BASE + "/v2/payment-requests")).andRespond(request -> {
                throw new IOException("Connection reset");
            });

            assertThatThrownBy(() -> gateway.createPaymentLink(newLink(1_000_001L, 1_000L, "NT1000001")))
                    .isInstanceOf(PayosGateway.Unavailable.class);
        }

        @Test
        @DisplayName("HTTP 200 body rỗng: Unavailable")
        void body_rong() {
            server.expect(requestTo(BASE + "/v2/payment-requests")).andRespond(withSuccess());

            assertThatThrownBy(() -> gateway.createPaymentLink(newLink(1_000_001L, 1_000L, "NT1000001")))
                    .isInstanceOf(PayosGateway.Unavailable.class);
        }

        @Test
        @DisplayName("HTTP 502 trang HTML của proxy: Unavailable")
        void http_502_html() {
            server.expect(requestTo(BASE + "/v2/payment-requests"))
                    .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                            .contentType(MediaType.TEXT_HTML)
                            .body("<html>Bad Gateway</html>"));

            assertThatThrownBy(() -> gateway.createPaymentLink(newLink(1_000_001L, 1_000L, "NT1000001")))
                    .isInstanceOf(PayosGateway.Unavailable.class);
        }

        @Test
        @DisplayName("HTTP 503 kèm envelope JSON: Unavailable (5xx là lỗi tạm thời theo hợp đồng của port)")
        void http_503_json_la_tam_thoi() {
            // Hợp đồng PayosGateway: Unavailable = "mạng, timeout, 5xx — thử lại được". Một 503 có body JSON
            // vẫn là 5xx; quy nó về Rejected khiến saga trả 502 "đừng thử lại" cho một sự cố tạm thời.
            server.expect(requestTo(BASE + "/v2/payment-requests"))
                    .andRespond(withServerError()
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"code\":\"99\",\"desc\":\"Hệ thống đang bảo trì\"}"));

            assertThatThrownBy(() -> gateway.createPaymentLink(newLink(1_000_001L, 1_000L, "NT1000001")))
                    .isInstanceOf(PayosGateway.Unavailable.class);
        }
    }

    @Nested
    @DisplayName("Tra trạng thái link")
    class TraTrangThai {

        @Test
        @DisplayName("Lấy mã giao dịch của phần tử MỚI NHẤT trong transactions")
        void lay_giao_dich_moi_nhat() {
            ObjectNode data = JSON.createObjectNode();
            data.put("orderCode", 1_000_001L);
            data.put("amount", 3_000_000L);
            data.put("amountPaid", 3_000_000L);
            data.put("status", "PAID");
            data.putArray("transactions").addObject().put("reference", "FT-CU").put("amount", 1_000_000L);
            ((com.fasterxml.jackson.databind.node.ArrayNode) data.get("transactions"))
                    .addObject()
                    .put("reference", "FT-MOI")
                    .put("amount", 2_000_000L);
            server.expect(requestTo(BASE + "/v2/payment-requests/1000001"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(signedEnvelope(data), MediaType.APPLICATION_JSON));

            Optional<PayosGateway.Settlement> settlement = gateway.fetchSettlement(1_000_001L);

            assertThat(settlement).contains(new PayosGateway.Settlement("PAID", 3_000_000L, "FT-MOI"));
        }

        @Test
        @DisplayName("Chưa có giao dịch: mã giao dịch null, KHÔNG bịa bằng paymentLinkId")
        void chua_co_giao_dich() {
            ObjectNode data = JSON.createObjectNode();
            data.put("status", "PENDING");
            data.put("amountPaid", 0);
            data.put("paymentLinkId", "link-1");
            data.putArray("transactions");
            server.expect(requestTo(BASE + "/v2/payment-requests/1000001"))
                    .andRespond(withSuccess(signedEnvelope(data), MediaType.APPLICATION_JSON));

            assertThat(gateway.fetchSettlement(1_000_001L)).contains(new PayosGateway.Settlement("PENDING", 0, null));
        }

        @Test
        @DisplayName("payOS không biết orderCode: rỗng chứ không ném")
        void khong_biet_order_code() {
            server.expect(requestTo(BASE + "/v2/payment-requests/1000001"))
                    .andRespond(withSuccess(
                            "{\"code\":\"101\",\"desc\":\"Không tìm thấy\",\"data\":null}",
                            MediaType.APPLICATION_JSON));

            assertThat(gateway.fetchSettlement(1_000_001L)).isEmpty();
        }

        @Test
        @DisplayName("Chữ ký response sai: NÉM TIẾP, không quy về 'chưa có gì'")
        void chu_ky_sai_nem_tiep() {
            ObjectNode data = JSON.createObjectNode();
            data.put("status", "PAID");
            data.put("amountPaid", 3_000_000L);
            server.expect(requestTo(BASE + "/v2/payment-requests/1000001"))
                    .andRespond(withSuccess(
                            "{\"code\":\"00\",\"desc\":\"ok\",\"data\":%s,\"signature\":\"%s\"}"
                                    .formatted(data, "0".repeat(64)),
                            MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> gateway.fetchSettlement(1_000_001L))
                    .isInstanceOfSatisfying(PayosGateway.Rejected.class, e -> assertThat(e.code())
                            .isEqualTo("SIGNATURE_MISMATCH"));
        }

        @Test
        @DisplayName("Lỗi mạng: Unavailable, không quy về rỗng")
        void loi_mang() {
            server.expect(requestTo(BASE + "/v2/payment-requests/1000001")).andRespond(request -> {
                throw new java.net.SocketTimeoutException("Read timed out");
            });

            assertThatThrownBy(() -> gateway.fetchSettlement(1_000_001L)).isInstanceOf(PayosGateway.Unavailable.class);
        }
    }

    @Nested
    @DisplayName("Huỷ link và đăng ký webhook")
    class HuyVaDangKy {

        @Test
        @DisplayName("Huỷ link: POST đúng đường dẫn với lý do huỷ")
        void huy_link() {
            ObjectNode data = JSON.createObjectNode().put("status", "CANCELLED");
            server.expect(requestTo(BASE + "/v2/payment-requests/1000001/cancel"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.cancellationReason").value("Khách huỷ"))
                    .andRespond(withSuccess(signedEnvelope(data), MediaType.APPLICATION_JSON));

            gateway.cancelPaymentLink(1_000_001L, "Khách huỷ");

            server.verify();
        }

        @Test
        @DisplayName("Huỷ link không có lý do: dùng lý do mặc định, không gửi null")
        void huy_link_khong_ly_do() {
            server.expect(requestTo(BASE + "/v2/payment-requests/1000001/cancel"))
                    .andExpect(jsonPath("$.cancellationReason").value("Đơn hàng đã đóng"))
                    .andRespond(withSuccess(
                            signedEnvelope(JSON.createObjectNode().put("status", "CANCELLED")),
                            MediaType.APPLICATION_JSON));

            gateway.cancelPaymentLink(1_000_001L, null);

            server.verify();
        }

        @Test
        @DisplayName("Huỷ link đã thanh toán: Rejected mang mã payOS")
        void huy_link_da_thanh_toan() {
            server.expect(requestTo(BASE + "/v2/payment-requests/1000001/cancel"))
                    .andRespond(withSuccess(
                            "{\"code\":\"101\",\"desc\":\"Link đã thanh toán\"}", MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> gateway.cancelPaymentLink(1_000_001L, "x"))
                    .isInstanceOfSatisfying(PayosGateway.Rejected.class, e -> assertThat(e.code())
                            .isEqualTo("101"));
        }

        @Test
        @DisplayName("Đăng ký webhook: response KHÔNG ký vẫn được nhận")
        void dang_ky_webhook_khong_ky() {
            server.expect(requestTo(BASE + "/confirm-webhook"))
                    .andExpect(jsonPath("$.webhookUrl").value("https://api.example/hook"))
                    .andRespond(withSuccess(
                            "{\"code\":\"00\",\"desc\":\"success\",\"data\":{\"webhookUrl\":\"https://api.example/hook\","
                                    + "\"accountNumber\":\"V3CAS0001\",\"accountName\":\"NEXATICKET\"}}",
                            MediaType.APPLICATION_JSON));

            assertThat(gateway.confirmWebhook("https://api.example/hook"))
                    .isEqualTo(new PayosGateway.WebhookRegistration(
                            "https://api.example/hook", "V3CAS0001", "NEXATICKET"));
        }
    }

    @Nested
    @DisplayName("Kiểm webhook")
    class KiemWebhook {

        private ObjectNode webhookData(String reference) {
            ObjectNode data = JSON.createObjectNode();
            data.put("orderCode", 1_000_001L);
            data.put("amount", 3_000_000L);
            data.put("description", "NT1000001");
            data.put("reference", reference);
            data.put("paymentLinkId", "link-1000001");
            data.put("code", "00");
            data.put("desc", "success");
            data.put("currency", "VND");
            data.putNull("counterAccountName");
            return data;
        }

        private String envelope(String code, boolean success, JsonNode data, String signature) {
            return "{\"code\":\"%s\",\"desc\":\"success\",\"success\":%s,\"data\":%s,\"signature\":\"%s\"}"
                    .formatted(code, success, data, signature);
        }

        @Test
        @DisplayName("Đã ký đúng: đọc orderCode, số tiền, mã giao dịch, thành công")
        void da_ky_dung() {
            ObjectNode data = webhookData("TF1");

            PayosGateway.WebhookVerification v =
                    gateway.verifyWebhook(envelope("00", true, data, PayosSignature.sign(data, KEY)));

            assertThat(v.isAuthentic()).isTrue();
            assertThat(v.payment())
                    .isEqualTo(new PayosGateway.WebhookPayment(1_000_001L, 3_000_000L, "TF1", true, "00", "success"));
        }

        @Test
        @DisplayName("Không có reference: dùng paymentLinkId làm mã giao dịch")
        void khong_reference_dung_link_id() {
            ObjectNode data = webhookData("");

            PayosGateway.WebhookVerification v =
                    gateway.verifyWebhook(envelope("00", true, data, PayosSignature.sign(data, KEY)));

            assertThat(v.payment().transactionReference()).isEqualTo("link-1000001");
        }

        @Test
        @DisplayName("Sửa số tiền sau khi ký: từ chối, không đọc trường nào")
        void sua_so_tien() {
            ObjectNode data = webhookData("TF1");
            String signature = PayosSignature.sign(data, KEY);
            data.put("amount", 3_000_001L);

            PayosGateway.WebhookVerification v = gateway.verifyWebhook(envelope("00", true, data, signature));

            assertThat(v.isAuthentic()).isFalse();
            assertThat(v.rejectionReason()).isEqualTo("Chữ ký không khớp");
        }

        @Test
        @DisplayName("Không có trường signature: từ chối")
        void khong_co_chu_ky() {
            String body = "{\"code\":\"00\",\"success\":true,\"data\":%s}".formatted(webhookData("TF1"));

            assertThat(gateway.verifyWebhook(body).isAuthentic()).isFalse();
        }

        @Test
        @DisplayName("Ký bằng khoá khác: từ chối")
        void khoa_khac() {
            ObjectNode data = webhookData("TF1");

            assertThat(gateway.verifyWebhook(envelope("00", true, data, PayosSignature.sign(data, "khoa-ke-gian")))
                            .isAuthentic())
                    .isFalse();
        }

        @Test
        @DisplayName("Envelope báo thành công nhưng data.code khác 00: đã ký nhưng KHÔNG thành công")
        void data_code_that_bai() {
            ObjectNode data = webhookData("TF1");
            data.put("code", "01");

            PayosGateway.WebhookVerification v =
                    gateway.verifyWebhook(envelope("00", true, data, PayosSignature.sign(data, KEY)));

            assertThat(v.isAuthentic()).isTrue();
            assertThat(v.payment().successful()).isFalse();
        }

        @Test
        @DisplayName("Envelope báo thất bại (code 01, success=false): không thành công dù data.code là 00")
        void envelope_that_bai() {
            ObjectNode data = webhookData("TF1");

            PayosGateway.WebhookVerification v =
                    gateway.verifyWebhook(envelope("01", false, data, PayosSignature.sign(data, KEY)));

            assertThat(v.payment().successful()).isFalse();
            assertThat(v.payment().providerCode()).isEqualTo("01");
        }

        @Test
        @DisplayName("Body không phải JSON, là mảng, hoặc thiếu data: từ chối kèm lý do riêng")
        void body_hong() {
            assertThat(gateway.verifyWebhook("not-json").rejectionReason()).isEqualTo("Body không phải JSON");
            assertThat(gateway.verifyWebhook("[1,2]").rejectionReason()).isEqualTo("Body không phải object JSON");
            assertThat(gateway.verifyWebhook("{\"code\":\"00\"}").rejectionReason())
                    .isEqualTo("Thiếu trường data");
            assertThat(gateway.verifyWebhook("{\"data\":\"chuoi\"}").rejectionReason())
                    .isEqualTo("Thiếu trường data");
            assertThat(gateway.verifyWebhook(null).isAuthentic()).isFalse();
        }

        @Test
        @DisplayName("Service chưa có checksum key: TỪ CHỐI mọi webhook, không mở cửa")
        void thieu_checksum_key() {
            PayosHttpGateway unconfigured = gateway(properties("client", "api", ""));
            ObjectNode data = webhookData("TF1");

            PayosGateway.WebhookVerification v =
                    unconfigured.verifyWebhook(envelope("00", true, data, PayosSignature.sign(data, KEY)));

            assertThat(v.isAuthentic()).isFalse();
            assertThat(v.rejectionReason()).contains("checksum key");
        }
    }
}
