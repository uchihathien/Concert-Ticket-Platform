// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.nexaticket.ordering.domain.port.InventoryPort;
import com.nexaticket.ordering.domain.port.PaymentPort;
import com.nexaticket.ordering.domain.port.PricingPort;
import com.nexaticket.ordering.domain.port.RemoteCallException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Anti-Corruption Layer sang ba service: phân biệt "bên kia trả lời dứt khoát" với "không biết bên
 * kia đã làm gì". Không có mạng thật — {@link MockRestServiceServer} đứng thay.
 */
class HttpAdaptersErrorMappingTest {

    private static final String PROBLEM = "application/problem+json";

    private MockRestServiceServer server;
    private RestClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://upstream");
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
    }

    @AfterEach
    void verifyAllCallsMade() {
        server.verify();
    }

    @Nested
    @DisplayName("Catalog — tra giá")
    class Catalog {

        private final UUID sessionId = UUID.randomUUID();
        private CatalogPricingAdapter adapter;

        @BeforeEach
        void setUp() {
            adapter = new CatalogPricingAdapter(client);
        }

        private String url() {
            return "http://upstream/internal/sessions/" + sessionId + "/pricing?promotionCode=SALE&subtotalVnd=3000000";
        }

        @Test
        @DisplayName("200: đọc hoa hồng và giảm giá, gửi đúng tạm tính server đã tính")
        void thanh_cong() {
            server.expect(requestTo(url()))
                    .andExpect(method(HttpMethod.GET))
                    .andExpect(queryParam("subtotalVnd", "3000000"))
                    .andRespond(
                            withSuccess("{\"commissionBps\":500,\"discountVnd\":120000}", MediaType.APPLICATION_JSON));

            assertThat(adapter.resolve(sessionId, "SALE", 3_000_000L)).isEqualTo(new PricingPort.Pricing(500, 120_000));
        }

        @Test
        @DisplayName("4xx với mã PROMOTION_INVALID: lỗi nghiệp vụ về mã khuyến mãi")
        void ma_khuyen_mai_sai() {
            server.expect(requestTo(url()))
                    .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                            .contentType(MediaType.parseMediaType(PROBLEM))
                            .body("{\"code\":\"PROMOTION_INVALID\",\"status\":422}"));

            assertThatThrownBy(() -> adapter.resolve(sessionId, "SALE", 3_000_000L))
                    .isInstanceOf(PricingPort.PromotionInvalidException.class);
        }

        @Test
        @DisplayName("404 vì sai đường dẫn: hỏng hạ tầng, KHÔNG đổ cho mã khuyến mãi của khách")
        void bon_le_bon_la_ha_tang() {
            server.expect(requestTo(url()))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND)
                            .contentType(MediaType.parseMediaType(PROBLEM))
                            .body("{\"code\":\"NOT_FOUND\"}"));

            assertThatThrownBy(() -> adapter.resolve(sessionId, "SALE", 3_000_000L))
                    .isInstanceOf(RemoteCallException.class)
                    .satisfies(
                            e -> assertThat(((RemoteCallException) e).service()).isEqualTo("catalog-service"));
        }

        @Test
        @DisplayName("4xx với body không phải JSON: hạ tầng, không phải mã khuyến mãi")
        void bon_xx_body_rac() {
            server.expect(requestTo(url()))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                            .contentType(MediaType.TEXT_HTML)
                            .body("<html>bad gateway</html>"));

            assertThatThrownBy(() -> adapter.resolve(sessionId, "SALE", 3_000_000L))
                    .isInstanceOf(RemoteCallException.class);
        }

        @Test
        @DisplayName("5xx: hạ tầng")
        void nam_xx() {
            server.expect(requestTo(url())).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

            assertThatThrownBy(() -> adapter.resolve(sessionId, "SALE", 3_000_000L))
                    .isInstanceOf(RemoteCallException.class);
        }

        @Test
        @DisplayName("Timeout mạng: hạ tầng, giữ nguyên nguyên nhân để lần theo")
        void timeout() {
            server.expect(requestTo(url())).andRespond(withException(new SocketTimeoutException("read timed out")));

            assertThatThrownBy(() -> adapter.resolve(sessionId, "SALE", 3_000_000L))
                    .isInstanceOf(RemoteCallException.class)
                    .hasRootCauseInstanceOf(SocketTimeoutException.class);
        }
    }

    @Nested
    @DisplayName("Inventory — đặt chỗ")
    class Inventory {

        private final UUID orderId = UUID.randomUUID();
        private final UUID holdId = UUID.randomUUID();
        private final UUID userId = UUID.randomUUID();
        private InventoryHttpAdapter adapter;

        @BeforeEach
        void setUp() {
            adapter = new InventoryHttpAdapter(client);
        }

        @Test
        @DisplayName("200: gửi orderId/holdId/userId, dịch JSON của Inventory sang kiểu của Ordering")
        void thanh_cong() {
            UUID session = UUID.randomUUID();
            UUID event = UUID.randomUUID();
            UUID org = UUID.randomUUID();
            UUID seat = UUID.randomUUID();
            UUID ticketType = UUID.randomUUID();
            server.expect(requestTo("http://upstream/internal/reservations"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                    .andExpect(jsonPath("$.holdId").value(holdId.toString()))
                    .andExpect(jsonPath("$.userId").value(userId.toString()))
                    .andRespond(withSuccess(
                            """
                            {"orderId":"%s","eventSessionId":"%s","eventId":"%s","organizationId":"%s",
                             "seats":[{"sessionSeatId":"%s","seatCode":"A-1","zoneCode":"A","admissionType":"SEATED",
                                       "seatLabel":"1","ticketTypeId":"%s","ticketTypeName":"VIP","priceVnd":1500000,
                                       "unknownField":"bo qua"}]}
                            """
                                    .formatted(orderId, session, event, org, seat, ticketType),
                            MediaType.APPLICATION_JSON));

            InventoryPort.Reservation reservation = adapter.reserve(orderId, holdId, userId);

            assertThat(reservation.eventSessionId()).isEqualTo(session);
            assertThat(reservation.eventId()).isEqualTo(event);
            assertThat(reservation.organizationId()).isEqualTo(org);
            assertThat(reservation.seats())
                    .containsExactly(
                            new InventoryPort.Seat(seat, "A-1", "A", "SEATED", "1", ticketType, "VIP", 1_500_000L));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"HOLD_EXPIRED", "HOLD_NOT_OWNED", "SEAT_UNAVAILABLE"})
        @DisplayName("4xx: lỗi nghiệp vụ, mang nguyên mã của Inventory")
        void bon_xx_mang_ma(String code) {
            server.expect(requestTo("http://upstream/internal/reservations"))
                    .andRespond(withStatus(HttpStatus.CONFLICT)
                            .contentType(MediaType.parseMediaType(PROBLEM))
                            .body("{\"code\":\"" + code + "\"}"));

            assertThatThrownBy(() -> adapter.reserve(orderId, holdId, userId))
                    .isInstanceOf(InventoryPort.SeatsUnavailableException.class)
                    .satisfies(e -> assertThat(((InventoryPort.SeatsUnavailableException) e).code())
                            .isEqualTo(code));
        }

        @Test
        @DisplayName("4xx không đọc được mã: coi như ghế đã bị lấy")
        void bon_xx_khong_co_ma() {
            server.expect(requestTo("http://upstream/internal/reservations"))
                    .andRespond(withStatus(HttpStatus.CONFLICT));

            assertThatThrownBy(() -> adapter.reserve(orderId, holdId, userId))
                    .isInstanceOf(InventoryPort.SeatsUnavailableException.class)
                    .satisfies(e -> assertThat(((InventoryPort.SeatsUnavailableException) e).code())
                            .isEqualTo("SEAT_UNAVAILABLE"));
        }

        @Test
        @DisplayName("5xx: KHÔNG biết đã đặt hay chưa ⇒ RemoteCallException để saga bù trừ")
        void nam_xx_la_khong_biet() {
            server.expect(requestTo("http://upstream/internal/reservations"))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

            assertThatThrownBy(() -> adapter.reserve(orderId, holdId, userId))
                    .isInstanceOf(RemoteCallException.class)
                    .satisfies(
                            e -> assertThat(((RemoteCallException) e).service()).isEqualTo("inventory-service"));
        }

        @Test
        @DisplayName("Mất kết nối: RemoteCallException")
        void mat_ket_noi() {
            server.expect(requestTo("http://upstream/internal/reservations"))
                    .andRespond(withException(new IOException("connection refused")));

            assertThatThrownBy(() -> adapter.reserve(orderId, holdId, userId)).isInstanceOf(RemoteCallException.class);
        }

        @Test
        @DisplayName("Nhả chỗ: DELETE đúng đơn; 5xx thành RemoteCallException để saga ghi lại")
        void nha_cho() {
            server.expect(requestTo("http://upstream/internal/reservations/" + orderId))
                    .andExpect(method(HttpMethod.DELETE))
                    .andRespond(withStatus(HttpStatus.NO_CONTENT));
            server.expect(requestTo("http://upstream/internal/reservations/" + orderId))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

            adapter.cancelReservation(orderId);
            assertThatThrownBy(() -> adapter.cancelReservation(orderId)).isInstanceOf(RemoteCallException.class);
        }
    }

    @Nested
    @DisplayName("Payment — mở link thanh toán")
    class Payment {

        private final UUID orderId = UUID.randomUUID();
        private final UUID orgId = UUID.randomUUID();
        private final Instant expiresAt = Instant.parse("2026-09-08T10:15:00Z");
        private PaymentHttpAdapter adapter;

        @BeforeEach
        void setUp() {
            adapter = new PaymentHttpAdapter(client);
        }

        @Test
        @DisplayName("200: gửi số tiền và hạn ISO-8601, nhận đủ snapshot link thanh toán")
        void thanh_cong() {
            server.expect(requestTo("http://upstream/internal/payment-intents"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.orderId").value(orderId.toString()))
                    .andExpect(jsonPath("$.organizationId").value(orgId.toString()))
                    .andExpect(jsonPath("$.amountVnd").value(2_700_000))
                    .andExpect(jsonPath("$.expiresAt").value("2026-09-08T10:15:00Z"))
                    .andRespond(withSuccess(
                            """
                            {"paymentReference":"NT1000001","vietQrPayload":"0002...","checkoutUrl":"https://pay.payos.vn/web/x",
                             "bankBin":"970422","bankAccountNumber":"V3CAS1","bankAccountName":"NEXATICKET"}
                            """,
                            MediaType.APPLICATION_JSON));

            assertThat(adapter.openIntent(orderId, orgId, 2_700_000L, expiresAt))
                    .isEqualTo(new PaymentPort.Intent(
                            "NT1000001", "0002...", "https://pay.payos.vn/web/x", "970422", "V3CAS1", "NEXATICKET"));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(ints = {400, 409, 500, 503})
        @DisplayName("Mọi mã lỗi HTTP khi mở intent: RemoteCallException — saga luôn bù trừ")
        void moi_loi_la_ha_tang(int status) {
            server.expect(requestTo("http://upstream/internal/payment-intents"))
                    .andRespond(withStatus(HttpStatus.valueOf(status)));

            assertThatThrownBy(() -> adapter.openIntent(orderId, orgId, 1L, expiresAt))
                    .isInstanceOf(RemoteCallException.class)
                    .satisfies(
                            e -> assertThat(((RemoteCallException) e).service()).isEqualTo("payment-service"));
        }

        @Test
        @DisplayName("200 body rỗng: RemoteCallException, không trả về intent null")
        void body_rong() {
            server.expect(requestTo("http://upstream/internal/payment-intents")).andRespond(withSuccess());

            assertThatThrownBy(() -> adapter.openIntent(orderId, orgId, 1L, expiresAt))
                    .isInstanceOf(RemoteCallException.class);
        }

        @Test
        @DisplayName("Huỷ intent: DELETE đúng đơn; timeout thành RemoteCallException")
        void huy_intent() {
            server.expect(requestTo("http://upstream/internal/payment-intents/" + orderId))
                    .andExpect(method(HttpMethod.DELETE))
                    .andRespond(withException(new SocketTimeoutException("timeout")));

            assertThatThrownBy(() -> adapter.cancelIntent(orderId)).isInstanceOf(RemoteCallException.class);
        }
    }
}
