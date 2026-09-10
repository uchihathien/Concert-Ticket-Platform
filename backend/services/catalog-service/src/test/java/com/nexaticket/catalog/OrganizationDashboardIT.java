// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.catalog.domain.port.SalesReportPort;
import com.nexaticket.catalog.domain.port.SeatStatusPort;
import com.nexaticket.catalog.domain.port.UpstreamUnavailableException;
import com.nexaticket.catalog.support.CatalogTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Bảng điều khiển của tổ chức, gồm cả cách nó cư xử khi service phía sau im lặng.
 *
 * <p>Inventory và Analytics được thay bằng hàng giả ở <b>ranh giới cổng</b>, không phải bằng một
 * HTTP server giả: hợp đồng HTTP giữa hai service là việc của adapter, còn ca test này hỏi một câu
 * khác — màn hình nói gì khi số liệu có, và nói gì khi không hỏi được.
 *
 * <p>Câu thứ hai mới là câu quan trọng. Trả 0đ vì analytics đang chết và trả 0đ vì chưa bán được
 * đồng nào là hai câu ngược nhau về công việc của ban tổ chức, và chỉ một trong hai là sự thật.
 */
class OrganizationDashboardIT extends CatalogTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper json;

    /**
     * Hàng giả điều khiển bằng biến tĩnh.
     *
     * <p>Không dùng Mockito bean: context của Spring được cache giữa các lớp test, nên một bean giả
     * mang trạng thái phải tự dọn — {@link #resetUpstreams()} làm việc đó trước mỗi ca.
     */
    static final Map<UUID, SeatStatusPort.SessionSeatStatus> SEAT_STATUS = new HashMap<>();

    static final List<SalesReportPort.SessionSales> SALES = new ArrayList<>();

    static boolean inventoryDown;

    static boolean analyticsDown;

    @BeforeEach
    void resetUpstreams() {
        SEAT_STATUS.clear();
        SALES.clear();
        inventoryDown = false;
        analyticsDown = false;
    }

    @TestConfiguration
    static class StubUpstreams {

        @Bean
        @Primary
        SeatStatusPort stubSeatStatus() {
            return eventSessionId -> {
                if (inventoryDown) {
                    throw new UpstreamUnavailableException("inventory-service", "test: inventory đang chết", null);
                }
                return Optional.ofNullable(SEAT_STATUS.get(eventSessionId));
            };
        }

        @Bean
        @Primary
        SalesReportPort stubSales() {
            return new SalesReportPort() {
                @Override
                public List<SessionSales> forEvent(UUID eventId) {
                    return sales();
                }

                @Override
                public List<SessionSales> forOrganization(UUID organizationId) {
                    return sales();
                }

                private List<SessionSales> sales() {
                    if (analyticsDown) {
                        throw new UpstreamUnavailableException("analytics-service", "test: analytics đang chết", null);
                    }
                    return List.copyOf(SALES);
                }
            };
        }
    }

    @Test
    @DisplayName("tổng quan: đếm sự kiện theo trạng thái và cộng sức chứa đã khai")
    void tong_quan_dem_dung() throws Exception {
        Fixture nhap = draft();
        Fixture daBan = draft();
        perform(post("/v1/organizations/" + ORG + "/events/" + daBan.eventId() + "/publish"));
        SALES.add(new SalesReportPort.SessionSales(daBan.sessionId(), daBan.eventId(), 12, 6_000_000, 12, 0, 0));

        JsonNode dashboard = json.readTree(perform(get("/v1/organizations/" + ORG + "/dashboard"))
                .getResponse()
                .getContentAsString());

        assertThat(dashboard.path("events")).hasSize(2);
        assertThat(dashboard.path("totals").path("eventCount").asInt()).isEqualTo(2);
        assertThat(dashboard.path("totals").path("publishedCount").asInt()).isEqualTo(1);
        assertThat(dashboard.path("totals").path("draftCount").asInt()).isEqualTo(1);
        assertThat(dashboard.path("totals").path("ticketsSold").asInt()).isEqualTo(12);
        assertThat(dashboard.path("totals").path("grossVnd").asLong()).isEqualTo(6_000_000);
        assertThat(dashboard.path("degraded")).isEmpty();
        assertThat(nhap.eventId()).isNotEqualTo(daBan.eventId());
    }

    @Test
    @DisplayName("master data: chi tiết + khu vực + trạng thái ghế + doanh thu trong một lời gọi")
    void master_data_day_du() throws Exception {
        Fixture f = draft();
        SEAT_STATUS.put(
                f.sessionId(),
                new SeatStatusPort.SessionSeatStatus(
                        f.sessionId(),
                        7,
                        List.of(
                                new SeatStatusPort.ZoneSeatStatus("VIP", "SEATED", 80, 5, 3, 12, 0),
                                new SeatStatusPort.ZoneSeatStatus("THUONG", "SEATED", 490, 0, 0, 10, 0))));
        SALES.add(new SalesReportPort.SessionSales(f.sessionId(), f.eventId(), 22, 11_000_000, 20, 1, 1));

        JsonNode master =
                json.readTree(perform(get("/v1/organizations/" + ORG + "/events/" + f.eventId() + "/master-data"))
                        .getResponse()
                        .getContentAsString());

        // Thông tin chi tiết và danh sách khu vực: dùng lại đúng hình dạng của màn hình sửa sự kiện.
        assertThat(master.path("event").path("venue").path("zones")).hasSize(2);
        assertThat(master.path("event").path("status").asText()).isEqualTo("DRAFT");

        // Trạng thái ghế, gộp theo khu và theo trạng thái.
        JsonNode seating = master.path("sessions").get(0).path("seating");
        assertThat(seating.path("availabilityVersion").asLong()).isEqualTo(7);
        assertThat(seating.path("zones")).hasSize(2);
        assertThat(seating.path("totals").path("sold").asInt()).isEqualTo(22);
        assertThat(seating.path("totals").path("total").asInt()).isEqualTo(600);

        // Báo cáo doanh thu.
        JsonNode sales = master.path("sessions").get(0).path("sales");
        assertThat(sales.path("ticketsSold").asInt()).isEqualTo(22);
        assertThat(sales.path("grossVnd").asLong()).isEqualTo(11_000_000);

        assertThat(master.path("totals").path("materializedSeats").asInt()).isEqualTo(600);
        assertThat(master.path("degraded")).isEmpty();
    }

    @Test
    @DisplayName("suất chưa lên bán: seating rỗng, và đó không phải lỗi")
    void suat_chua_len_ban_thi_khong_co_ton_kho() throws Exception {
        // Tồn kho chỉ được dựng lúc publish. Một sự kiện còn nháp không có gì để đếm, và màn hình
        // phải nói "chưa lên bán" chứ không phải "0 chỗ".
        Fixture f = draft();

        JsonNode master =
                json.readTree(perform(get("/v1/organizations/" + ORG + "/events/" + f.eventId() + "/master-data"))
                        .getResponse()
                        .getContentAsString());

        assertThat(master.path("sessions").get(0).path("seating").isNull()).isTrue();
        assertThat(master.path("degraded")).isEmpty();
        assertThat(master.path("totals").path("materializedSeats").asInt()).isZero();
    }

    @Test
    @DisplayName("inventory chết: vẫn trả 200 với phần catalog, và nói rõ thiếu vì sao")
    void inventory_chet_thi_van_mo_duoc_man_hinh() throws Exception {
        Fixture f = draft();
        inventoryDown = true;
        SALES.add(new SalesReportPort.SessionSales(f.sessionId(), f.eventId(), 5, 2_500_000, 5, 0, 0));

        MvcResult result = perform(get("/v1/organizations/" + ORG + "/events/" + f.eventId() + "/master-data"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode master = json.readTree(result.getResponse().getContentAsString());
        assertThat(master.path("degraded").toString()).contains("inventory-service");
        // Phần hỏi được vẫn có mặt: mất tất cả để khỏi mất một phần là đánh đổi ngược.
        assertThat(master.path("event").path("venue").path("zones")).hasSize(2);
        assertThat(master.path("sessions")
                        .get(0)
                        .path("sales")
                        .path("ticketsSold")
                        .asInt())
                .isEqualTo(5);
    }

    @Test
    @DisplayName("analytics chết: tổng quan không báo 0đ, nó báo là không hỏi được")
    void analytics_chet_thi_khong_bao_khong_dong() throws Exception {
        draft();
        analyticsDown = true;

        JsonNode dashboard = json.readTree(perform(get("/v1/organizations/" + ORG + "/dashboard"))
                .getResponse()
                .getContentAsString());

        assertThat(dashboard.path("events")).hasSize(1);
        assertThat(dashboard.path("degraded").toString()).contains("analytics-service");
    }

    @Test
    @DisplayName("sự kiện của tổ chức khác: 404 trước khi chạm tới dữ liệu")
    void khong_xem_duoc_su_kien_to_chuc_khac() throws Exception {
        Fixture f = draft();

        MvcResult result = mockMvc.perform(
                        get("/v1/organizations/" + OTHER_ORG + "/events/" + f.eventId() + "/master-data")
                                .header("Authorization", BEARER))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    // --- dựng dữ liệu ------------------------------------------------------

    private record Fixture(UUID eventId, UUID sessionId) {}

    /** Một sự kiện nháp publish được: địa điểm hai khu (VIP 100 + Thường 500), một suất, hai hạng vé. */
    private Fixture draft() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        UUID venueId = UUID.fromString(json.readTree(perform(post("/v1/organizations/" + ORG + "/venues")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(Map.of("name", "Nhà hát " + suffix, "city", "Hà Nội"))))
                        .getResponse()
                        .getContentAsString())
                .path("id")
                .asText());

        perform(post("/v1/organizations/" + ORG + "/venues/" + venueId + "/zones")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "zoneCode", "VIP", "name", "Khu VIP", "kind", "SEATED", "rowCount", 10, "seatsPerRow", 10))));
        perform(post("/v1/organizations/" + ORG + "/venues/" + venueId + "/zones")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "zoneCode",
                        "THUONG",
                        "name",
                        "Khu Thường",
                        "kind",
                        "SEATED",
                        "rowCount",
                        20,
                        "seatsPerRow",
                        25))));

        JsonNode event = json.readTree(perform(post("/v1/organizations/" + ORG + "/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "venueId",
                                venueId,
                                "title",
                                "Sự kiện " + suffix,
                                "slug",
                                "su-kien-" + suffix,
                                "category",
                                "nhac-song"))))
                .getResponse()
                .getContentAsString());
        UUID eventId = UUID.fromString(event.path("id").asText());

        Instant startsAt = Instant.now().plus(Duration.ofDays(30));
        JsonNode withSession =
                json.readTree(perform(post("/v1/organizations/" + ORG + "/events/" + eventId + "/sessions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of(
                                        "startsAt", startsAt.toString(),
                                        "salesOpenAt", Instant.now().toString(),
                                        "salesCloseAt", startsAt.toString()))))
                        .getResponse()
                        .getContentAsString());
        UUID sessionId =
                UUID.fromString(withSession.path("sessions").get(0).path("id").asText());

        for (JsonNode zone : event.path("venue").path("zones")) {
            perform(post("/v1/organizations/" + ORG + "/events/" + eventId + "/sessions/" + sessionId + "/ticket-types")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of(
                            "venueZoneId",
                            zone.path("id").asText(),
                            "name",
                            "Hạng " + zone.path("zoneCode").asText(),
                            "priceVnd",
                            500_000))));
        }
        return new Fixture(eventId, sessionId);
    }

    private MvcResult perform(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", BEARER)).andReturn();
    }
}
