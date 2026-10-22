// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.domain.model.OrderSummary;
import com.nexaticket.aichatbox.domain.model.PlacedOrder;
import com.nexaticket.aichatbox.domain.port.BookingRejectedException;
import com.nexaticket.aichatbox.domain.port.OrderNotFoundException;
import com.nexaticket.aichatbox.domain.port.RemoteCallException;
import com.nexaticket.aichatbox.support.StubHttpServer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Anti-corruption layer sang ordering-service: đi bằng token của khách, dịch mã lỗi cho khách. */
@DisplayName("OrderingHttpAdapter")
class OrderingHttpAdapterTest {

    private static final String TOKEN = "jwt-cua-khach.abc.def";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StubHttpServer server = new StubHttpServer();
    private final UUID orderId = UUID.randomUUID();

    @AfterEach
    void stopServer() {
        server.close();
    }

    private OrderingHttpAdapter adapterAt(String baseUrl) {
        return new OrderingHttpAdapter(HttpTestClients.client(baseUrl));
    }

    private OrderingHttpAdapter adapter() {
        return adapterAt(server.baseUrl());
    }

    private String orderJson() {
        return "{\"id\":\"" + orderId + "\",\"orderNumber\":\"NT-240001\",\"eventId\":\"" + UUID.randomUUID()
                + "\",\"eventSessionId\":\"" + UUID.randomUUID() + "\",\"status\":\"PAID\",\"totalVnd\":2250000,"
                + "\"paymentExpiresAt\":null,\"paidAt\":\"2026-10-07T03:00:00Z\","
                + "\"vietQrPayload\":\"00020101...\",\"checkoutUrl\":\"https://pay/x\",\"paymentReference\":\"REF\","
                + "\"items\":["
                + "{\"zoneCode\":\"A\",\"seatLabel\":\"20\",\"ticketTypeName\":\"VIP\",\"unitPriceVnd\":1800000},"
                + "{\"zoneCode\":\"GA\",\"seatLabel\":null,\"ticketTypeName\":null,\"unitPriceVnd\":450000},"
                + "{\"zoneCode\":\"B\",\"seatLabel\":\" \",\"ticketTypeName\":\"Thường\",\"unitPriceVnd\":0}]}";
    }

    @Nested
    @DisplayName("fetchOrder")
    class FetchOrder {

        @Test
        @DisplayName("gửi token của KHÁCH ở header Authorization tới đường của khách, không phải /internal")
        void di_bang_token_cua_khach() {
            server.respond(200, orderJson());

            adapter().fetchOrder(orderId, TOKEN);

            StubHttpServer.Received sent = server.only();
            assertThat(sent.method()).isEqualTo("GET");
            assertThat(sent.path()).isEqualTo("/v1/orders/" + orderId).doesNotContain("internal");
            assertThat(sent.header("authorization")).isEqualTo("Bearer " + TOKEN);
        }

        @Test
        @DisplayName("ánh xạ đơn, mô tả vé ngồi / vé đứng / thiếu tên loại vé, bỏ qua trường nhạy cảm")
        void anh_xa_don() {
            server.respond(200, orderJson());

            OrderSummary order = adapter().fetchOrder(orderId, TOKEN);

            assertThat(order.orderId()).isEqualTo(orderId);
            assertThat(order.orderNumber()).isEqualTo("NT-240001");
            assertThat(order.status()).isEqualTo("PAID");
            assertThat(order.totalVnd()).isEqualTo(2_250_000L);
            assertThat(order.paymentExpiresAt()).isNull();
            assertThat(order.paidAt()).isEqualTo(Instant.parse("2026-10-07T03:00:00Z"));
            assertThat(order.items())
                    .extracting(OrderSummary.Item::description)
                    .containsExactly("VIP · A · ghế 20", "GA · vé đứng", "Thường · B · vé đứng");
            assertThat(order.items())
                    .extracting(OrderSummary.Item::unitPriceVnd)
                    .containsExactly(1_800_000L, 450_000L, 0L);
            // Không kiểu nào của domain có chỗ chứa QR / URL thanh toán / mã tham chiếu.
            assertThat(order.toString()).doesNotContain("00020101").doesNotContain("REF");
        }

        @Test
        @DisplayName("đơn không có items thì là danh sách rỗng")
        void khong_co_items() {
            server.respond(
                    200, "{\"id\":\"" + orderId + "\",\"orderNumber\":\"N\",\"status\":\"PAID\",\"totalVnd\":1}");

            assertThat(adapter().fetchOrder(orderId, TOKEN).items()).isEmpty();
        }

        @Test
        @DisplayName("404 và 403 đều thành OrderNotFoundException — không để khách dò mã đơn nào có thật")
        void khong_phan_biet_404_va_403() {
            for (int status : new int[] {404, 403}) {
                try (StubHttpServer local = new StubHttpServer()) {
                    local.respond(status, "{\"code\":\"X\"}");

                    assertThatThrownBy(() -> adapterAt(local.baseUrl()).fetchOrder(orderId, TOKEN))
                            .as("HTTP %d", status)
                            .isInstanceOf(OrderNotFoundException.class);
                }
            }
        }

        @Test
        @DisplayName("401 (token hết hạn giữa chừng) và 5xx là sự cố hạ tầng, không phải 'không có đơn'")
        void loi_401_va_5xx_la_su_co() {
            for (int status : new int[] {401, 400, 500, 503}) {
                try (StubHttpServer local = new StubHttpServer()) {
                    local.respond(status, "{}");

                    assertThatThrownBy(() -> adapterAt(local.baseUrl()).fetchOrder(orderId, TOKEN))
                            .as("HTTP %d", status)
                            .isInstanceOfSatisfying(RemoteCallException.class, e -> {
                                assertThat(e.service()).isEqualTo("ordering-service");
                                assertThat(e.getMessage()).doesNotContain(TOKEN);
                            });
                }
            }
        }

        @Test
        @DisplayName("body rỗng ở 200 là RemoteCallException, không phải đơn null")
        void body_rong() {
            server.respond(200, "");

            assertThatThrownBy(() -> adapter().fetchOrder(orderId, TOKEN))
                    .isInstanceOf(RemoteCallException.class)
                    .hasMessageContaining("rỗng");
        }

        @Test
        @DisplayName("mạng đứt hoặc JSON hỏng là RemoteCallException mang nguyên nhân gốc, không lộ token")
        void mang_dut_va_json_hong() {
            assertThatThrownBy(() -> adapterAt(StubHttpServer.deadBaseUrl()).fetchOrder(orderId, TOKEN))
                    .isInstanceOfSatisfying(RemoteCallException.class, e -> {
                        assertThat(e.getCause()).isNotNull();
                        assertThat(e.getMessage()).doesNotContain(TOKEN);
                    });

            server.respond(200, "<html>bad gateway</html>");
            assertThatThrownBy(() -> adapter().fetchOrder(orderId, TOKEN)).isInstanceOf(RemoteCallException.class);
        }
    }

    @Nested
    @DisplayName("listMyOrders")
    class ListMyOrders {

        @Test
        @DisplayName("gọi /v1/me/orders với limit và offset=0, kèm token của khách")
        void goi_dung_duong() {
            server.respond(200, "[" + orderJson() + "]");

            List<OrderSummary> orders = adapter().listMyOrders(TOKEN, 5);

            StubHttpServer.Received sent = server.only();
            assertThat(sent.path()).isEqualTo("/v1/me/orders");
            assertThat(sent.query()).contains("limit=5").contains("offset=0");
            assertThat(sent.header("authorization")).isEqualTo("Bearer " + TOKEN);
            assertThat(orders).extracting(OrderSummary::orderNumber).containsExactly("NT-240001");
        }

        @Test
        @DisplayName("limit không dương được nâng lên 1")
        void limit_toi_thieu_1() {
            server.respond(200, "[]");
            OrderingHttpAdapter adapter = adapter();

            adapter.listMyOrders(TOKEN, 0);
            adapter.listMyOrders(TOKEN, -3);

            assertThat(server.received())
                    .extracting(StubHttpServer.Received::query)
                    .allSatisfy(q -> assertThat(q).contains("limit=1"));
        }

        @Test
        @DisplayName("mảng rỗng và body rỗng đều là danh sách rỗng")
        void rong() {
            server.respond(200, "[]");
            assertThat(adapter().listMyOrders(TOKEN, 5)).isEmpty();

            try (StubHttpServer local = new StubHttpServer()) {
                local.respond(200, "");
                assertThat(adapterAt(local.baseUrl()).listMyOrders(TOKEN, 5)).isEmpty();
            }
        }

        @Test
        @DisplayName("non-2xx và mạng đứt là RemoteCallException — khác với 'khách không có đơn'")
        void loi() {
            for (int status : new int[] {401, 403, 404, 500}) {
                try (StubHttpServer local = new StubHttpServer()) {
                    local.respond(status, "{}");

                    assertThatThrownBy(() -> adapterAt(local.baseUrl()).listMyOrders(TOKEN, 5))
                            .as("HTTP %d", status)
                            .isInstanceOf(RemoteCallException.class);
                }
            }
            assertThatThrownBy(() -> adapterAt(StubHttpServer.deadBaseUrl()).listMyOrders(TOKEN, 5))
                    .isInstanceOf(RemoteCallException.class)
                    .hasCauseInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("placeOrder")
    class PlaceOrder {

        private final UUID holdId = UUID.randomUUID();

        private String created() {
            return "{\"orderId\":\"" + orderId + "\",\"orderNumber\":\"NT-240010\",\"totalVnd\":3600000,"
                    + "\"checkoutUrl\":\"https://pay.payos.vn/web/xyz\",\"paymentExpiresAt\":\"2026-10-07T03:15:00Z\","
                    + "\"vietQrPayload\":\"00020101\",\"paymentReference\":\"REF\"}";
        }

        @Test
        @DisplayName("gửi holdId, token và Idempotency-Key; ánh xạ đơn vừa tạo, bỏ QR và mã tham chiếu")
        void gui_dung_va_anh_xa() throws Exception {
            server.respond(201, created());

            PlacedOrder placed = adapter().placeOrder(holdId, TOKEN, "khoa-idem-1");

            StubHttpServer.Received sent = server.only();
            assertThat(sent.method()).isEqualTo("POST");
            assertThat(sent.path()).isEqualTo("/v1/orders");
            assertThat(sent.header("authorization")).isEqualTo("Bearer " + TOKEN);
            assertThat(sent.header("idempotency-key")).isEqualTo("khoa-idem-1");
            assertThat(sent.header("content-type")).contains("application/json");
            JsonNode body = JSON.readTree(sent.body());
            assertThat(body.get("holdId").asText()).isEqualTo(holdId.toString());
            assertThat(body.size()).isEqualTo(1);

            assertThat(placed.orderId()).isEqualTo(orderId);
            assertThat(placed.orderNumber()).isEqualTo("NT-240010");
            assertThat(placed.totalVnd()).isEqualTo(3_600_000L);
            assertThat(placed.checkoutUrl()).isEqualTo("https://pay.payos.vn/web/xyz");
            assertThat(placed.paymentExpiresAt()).isEqualTo(Instant.parse("2026-10-07T03:15:00Z"));
        }

        @Test
        @DisplayName("mã lỗi nghiệp vụ 4xx được dịch thành lý do cho khách, theo bảng của ordering")
        void dich_ma_loi() {
            String[][] cases = {
                {"HOLD_EXPIRED", "hết hạn"},
                {"SEAT_UNAVAILABLE", "người khác lấy trước"},
                {"PROMOTION_INVALID", "khuyến mãi không hợp lệ"},
                {"ORDER_NOT_OPEN", "không còn ở trạng thái chờ thanh toán"},
            };
            for (String[] c : cases) {
                try (StubHttpServer local = new StubHttpServer()) {
                    local.respond(409, "{\"code\":\"" + c[0] + "\",\"detail\":\"câu dành cho client khác\"}");

                    assertThatThrownBy(() -> adapterAt(local.baseUrl()).placeOrder(holdId, TOKEN, "k"))
                            .as(c[0])
                            .isInstanceOfSatisfying(BookingRejectedException.class, e -> {
                                assertThat(e.reason()).contains(c[1]);
                                // `detail` của service kia viết cho client của họ, không đi vào mô hình.
                                assertThat(e.reason()).doesNotContain("câu dành cho client khác");
                            });
                }
            }
        }

        @Test
        @DisplayName("4xx với mã lạ, body không phải JSON hoặc không có code thì rơi về câu chung — vẫn là 'từ chối'")
        void ma_la_roi_ve_cau_chung() {
            for (String body : new String[] {"{\"code\":\"MA_LA\"}", "<html>400</html>", "", "{\"detail\":\"x\"}"}) {
                try (StubHttpServer local = new StubHttpServer()) {
                    local.respond(422, body);

                    assertThatThrownBy(() -> adapterAt(local.baseUrl()).placeOrder(holdId, TOKEN, "k"))
                            .as("body=[%s]", body)
                            .isInstanceOfSatisfying(BookingRejectedException.class, e -> assertThat(e.reason())
                                    .isEqualTo("Ordering không nhận đơn này."));
                }
            }
        }

        @Test
        @DisplayName("5xx là RemoteCallException, không phải bị từ chối — khách nên thử lại thay vì chọn lại chỗ")
        void loi_5xx() {
            for (int status : new int[] {500, 502, 503}) {
                try (StubHttpServer local = new StubHttpServer()) {
                    local.respond(status, "{\"code\":\"HOLD_EXPIRED\"}");

                    assertThatThrownBy(() -> adapterAt(local.baseUrl()).placeOrder(holdId, TOKEN, "k"))
                            .as("HTTP %d", status)
                            .isInstanceOf(RemoteCallException.class);
                    // Lệnh GHI không được tự thử lại ở tầng HTTP.
                    assertThat(local.received()).hasSize(1);
                }
            }
        }

        @Test
        @DisplayName("body rỗng, mạng đứt và JSON hỏng đều là RemoteCallException")
        void body_rong_mang_dut() {
            server.respond(201, "");
            assertThatThrownBy(() -> adapter().placeOrder(holdId, TOKEN, "k"))
                    .isInstanceOf(RemoteCallException.class)
                    .hasMessageContaining("rỗng");

            assertThatThrownBy(() -> adapterAt(StubHttpServer.deadBaseUrl()).placeOrder(holdId, TOKEN, "k"))
                    .isInstanceOf(RemoteCallException.class)
                    .hasCauseInstanceOf(RuntimeException.class);

            server.respond(201, "không phải json");
            assertThatThrownBy(() -> adapter().placeOrder(holdId, TOKEN, "k")).isInstanceOf(RemoteCallException.class);
        }
    }
}
