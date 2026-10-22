// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.aichatbox.domain.model.TicketHold;
import com.nexaticket.aichatbox.domain.model.ZoneAdmission;
import com.nexaticket.aichatbox.domain.port.BookingRejectedException;
import com.nexaticket.aichatbox.domain.port.RemoteCallException;
import com.nexaticket.aichatbox.support.StubHttpServer;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Anti-corruption layer sang inventory-service: giữ chỗ và nhả chỗ. */
@DisplayName("InventoryHttpAdapter")
class InventoryHttpAdapterTest {

    private static final String TOKEN = "jwt-cua-khach.abc.def";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final StubHttpServer server = new StubHttpServer();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID holdId = UUID.randomUUID();

    @AfterEach
    void stopServer() {
        server.close();
    }

    private InventoryHttpAdapter adapterAt(String baseUrl) {
        return new InventoryHttpAdapter(HttpTestClients.client(baseUrl));
    }

    private InventoryHttpAdapter adapter() {
        return adapterAt(server.baseUrl());
    }

    private String created(String seatIdsJson) {
        return "{\"holdId\":\"" + holdId + "\",\"expiresAt\":\"2026-10-07T03:10:00Z\",\"seatIds\":" + seatIdsJson
                + ",\"availabilityVersion\":7}";
    }

    @Nested
    @DisplayName("holdZone")
    class HoldZone {

        @Test
        @DisplayName("vé ngồi: zone vào seatedZones, standing để trống; kèm token và Idempotency-Key")
        void ve_ngoi() throws Exception {
            server.respond(201, created("[\"" + UUID.randomUUID() + "\",\"" + UUID.randomUUID() + "\"]"));

            adapter().holdZone(sessionId, "A", 2, ZoneAdmission.SEATED, TOKEN, "khoa-1");

            StubHttpServer.Received sent = server.only();
            assertThat(sent.method()).isEqualTo("POST");
            assertThat(sent.path()).isEqualTo("/v1/sessions/" + sessionId + "/holds");
            assertThat(sent.header("authorization")).isEqualTo("Bearer " + TOKEN);
            assertThat(sent.header("idempotency-key")).isEqualTo("khoa-1");
            JsonNode body = JSON.readTree(sent.body());
            assertThat(body.get("seatIds")).isEmpty();
            assertThat(body.get("standing")).isEmpty();
            assertThat(body.get("seatedZones")).hasSize(1);
            assertThat(body.get("seatedZones").get(0).get("zoneCode").asText()).isEqualTo("A");
            assertThat(body.get("seatedZones").get(0).get("quantity").asInt()).isEqualTo(2);
        }

        @Test
        @DisplayName("vé đứng: zone vào standing, seatedZones để trống")
        void ve_dung() throws Exception {
            server.respond(201, created("[]"));

            adapter().holdZone(sessionId, "GA", 3, ZoneAdmission.STANDING, TOKEN, "khoa-2");

            JsonNode body = JSON.readTree(server.only().body());
            assertThat(body.get("seatedZones")).isEmpty();
            assertThat(body.get("standing")).hasSize(1);
            assertThat(body.get("standing").get(0).get("zoneCode").asText()).isEqualTo("GA");
            assertThat(body.get("standing").get(0).get("quantity").asInt()).isEqualTo(3);
        }

        @Test
        @DisplayName("số chỗ giữ được: vé ngồi đếm theo seatIds hệ thống chọn, vé đứng (không seatIds) lấy số xin")
        void so_cho_giu_duoc() {
            server.respond(201, created("[\"" + UUID.randomUUID() + "\"]"));
            TicketHold seated = adapter().holdZone(sessionId, "A", 2, ZoneAdmission.SEATED, TOKEN, "k");
            assertThat(seated.quantity()).isEqualTo(1);
            assertThat(seated.holdId()).isEqualTo(holdId);
            assertThat(seated.expiresAt()).isEqualTo(Instant.parse("2026-10-07T03:10:00Z"));

            for (String seatIds : new String[] {"[]", "null"}) {
                try (StubHttpServer local = new StubHttpServer()) {
                    local.respond(201, created(seatIds));
                    TicketHold standing =
                            adapterAt(local.baseUrl()).holdZone(sessionId, "GA", 4, ZoneAdmission.STANDING, TOKEN, "k");
                    assertThat(standing.quantity()).as("seatIds=%s", seatIds).isEqualTo(4);
                }
            }
        }

        @Test
        @DisplayName("mã lỗi nghiệp vụ 4xx được dịch thành lý do cho khách")
        void dich_ma_loi() {
            String[][] cases = {
                {"ZONE_SOLD_OUT", "không còn đủ chỗ"},
                {"SEAT_UNAVAILABLE", "vừa có người khác giữ"},
                {"SALES_CLOSED", "đóng bán vé"},
                {"SESSION_NOT_FOUND", "getEventDetails"},
                {"HOLD_LIMIT_EXCEEDED", "hạn mức một lần giữ chỗ"},
                {"CUSTOMER_LIMIT_EXCEEDED", "đã đạt hạn mức mua"},
            };
            for (String[] c : cases) {
                try (StubHttpServer local = new StubHttpServer()) {
                    local.respond(409, "{\"code\":\"" + c[0] + "\",\"detail\":\"chi tiết nội bộ\"}");

                    assertThatThrownBy(() -> adapterAt(local.baseUrl())
                                    .holdZone(sessionId, "A", 1, ZoneAdmission.SEATED, TOKEN, "k"))
                            .as(c[0])
                            .isInstanceOfSatisfying(BookingRejectedException.class, e -> {
                                assertThat(e.reason()).contains(c[1]);
                                assertThat(e.reason()).doesNotContain("chi tiết nội bộ");
                            });
                }
            }
        }

        @Test
        @DisplayName("4xx mã lạ hoặc body hỏng rơi về câu chung — vẫn là bị từ chối, không phải sự cố")
        void ma_la_roi_ve_cau_chung() {
            for (String body : new String[] {"{\"code\":\"LA\"}", "<html/>", ""}) {
                try (StubHttpServer local = new StubHttpServer()) {
                    local.respond(400, body);

                    assertThatThrownBy(() -> adapterAt(local.baseUrl())
                                    .holdZone(sessionId, "A", 1, ZoneAdmission.SEATED, TOKEN, "k"))
                            .as("body=[%s]", body)
                            .isInstanceOfSatisfying(BookingRejectedException.class, e -> assertThat(e.reason())
                                    .isEqualTo("Inventory không nhận yêu cầu giữ chỗ này."));
                }
            }
        }

        @Test
        @DisplayName("5xx là RemoteCallException và lệnh ghi không bị thử lại")
        void loi_5xx() {
            for (int status : new int[] {500, 503}) {
                try (StubHttpServer local = new StubHttpServer()) {
                    local.respond(status, "{}");

                    assertThatThrownBy(() -> adapterAt(local.baseUrl())
                                    .holdZone(sessionId, "A", 1, ZoneAdmission.SEATED, TOKEN, "k"))
                            .as("HTTP %d", status)
                            .isInstanceOfSatisfying(RemoteCallException.class, e -> assertThat(e.service())
                                    .isEqualTo("inventory-service"));
                    assertThat(local.received()).hasSize(1);
                }
            }
        }

        @Test
        @DisplayName("body rỗng, mạng đứt, JSON hỏng đều là RemoteCallException")
        void body_rong_va_mang_dut() {
            server.respond(201, "");
            assertThatThrownBy(() -> adapter().holdZone(sessionId, "A", 1, ZoneAdmission.SEATED, TOKEN, "k"))
                    .isInstanceOf(RemoteCallException.class)
                    .hasMessageContaining("rỗng");

            assertThatThrownBy(() -> adapterAt(StubHttpServer.deadBaseUrl())
                            .holdZone(sessionId, "A", 1, ZoneAdmission.SEATED, TOKEN, "k"))
                    .isInstanceOf(RemoteCallException.class)
                    .hasCauseInstanceOf(RuntimeException.class);

            server.respond(201, "{không phải json");
            assertThatThrownBy(() -> adapter().holdZone(sessionId, "A", 1, ZoneAdmission.SEATED, TOKEN, "k"))
                    .isInstanceOf(RemoteCallException.class);
        }
    }

    @Nested
    @DisplayName("releaseHold")
    class ReleaseHold {

        @Test
        @DisplayName("gửi DELETE /v1/holds/{id} kèm token của khách")
        void gui_delete() {
            server.respond(204, "");

            adapter().releaseHold(holdId, TOKEN);

            StubHttpServer.Received sent = server.only();
            assertThat(sent.method()).isEqualTo("DELETE");
            assertThat(sent.path()).isEqualTo("/v1/holds/" + holdId);
            assertThat(sent.header("authorization")).isEqualTo("Bearer " + TOKEN);
        }

        @Test
        @DisplayName("không bao giờ ném: 404, 500 hay mạng đứt đều nuốt lại vì chỗ giữ tự hết hạn")
        void khong_bao_gio_nem() {
            for (int status : new int[] {404, 409, 500}) {
                try (StubHttpServer local = new StubHttpServer()) {
                    local.respond(status, "{}");

                    assertThatCode(() -> adapterAt(local.baseUrl()).releaseHold(holdId, TOKEN))
                            .as("HTTP %d", status)
                            .doesNotThrowAnyException();
                }
            }
            assertThatCode(() -> adapterAt(StubHttpServer.deadBaseUrl()).releaseHold(holdId, TOKEN))
                    .doesNotThrowAnyException();
        }
    }
}
