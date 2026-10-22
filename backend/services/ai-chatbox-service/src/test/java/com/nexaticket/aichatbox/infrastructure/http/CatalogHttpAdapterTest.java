// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.aichatbox.domain.model.EventBrief;
import com.nexaticket.aichatbox.domain.model.EventDetail;
import com.nexaticket.aichatbox.domain.model.ZoneAdmission;
import com.nexaticket.aichatbox.domain.port.RemoteCallException;
import com.nexaticket.aichatbox.support.StubHttpServer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Anti-corruption layer sang catalog-service, trên máy chủ HTTP cục bộ. */
@DisplayName("CatalogHttpAdapter")
class CatalogHttpAdapterTest {

    private static final String EMPTY_PAGE = "{\"items\":[]}";

    private final StubHttpServer server = new StubHttpServer();

    @AfterEach
    void stopServer() {
        server.close();
    }

    private CatalogHttpAdapter adapterAt(String baseUrl) {
        return new CatalogHttpAdapter(HttpTestClients.client(baseUrl));
    }

    private CatalogHttpAdapter adapter() {
        return adapterAt(server.baseUrl());
    }

    private static String card(String slug, String title) {
        return "{\"slug\":\"" + slug + "\",\"title\":\"" + title + "\",\"category\":\"nhac-song\","
                + "\"city\":\"Hà Nội\",\"venueName\":\"Nhà hát Lớn\",\"nextSessionAt\":\"2026-11-20T13:00:00Z\","
                + "\"fromPriceVnd\":450000,\"sessionCount\":2,\"posterUrl\":\"https://x/p.png\"}";
    }

    @Nested
    @DisplayName("searchEvents")
    class SearchEvents {

        @Test
        @DisplayName("ánh xạ thẻ sự kiện sang EventBrief, bỏ qua trường lạ")
        void anh_xa_the_su_kien() {
            server.respond(200, "{\"items\":[" + card("dem-nhac-trinh", "Đêm nhạc Trịnh") + "]}");

            List<EventBrief> found = adapter().searchEvents("Trịnh", null, null, 5);

            assertThat(found).hasSize(1);
            EventBrief brief = found.get(0);
            assertThat(brief.slug()).isEqualTo("dem-nhac-trinh");
            assertThat(brief.title()).isEqualTo("Đêm nhạc Trịnh");
            assertThat(brief.city()).isEqualTo("Hà Nội");
            assertThat(brief.venueName()).isEqualTo("Nhà hát Lớn");
            assertThat(brief.nextSessionAt()).isEqualTo(Instant.parse("2026-11-20T13:00:00Z"));
            assertThat(brief.fromPriceVnd()).isEqualTo(450_000L);
            assertThat(brief.sessionCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("tham số rỗng hoặc toàn khoảng trắng KHÔNG được gửi — query= khác hẳn không có query")
        void tham_so_rong_khong_duoc_gui() {
            server.respond(200, "{\"items\":[" + card("a", "A") + "]}");

            adapter().searchEvents("  ", "", "   ", 5);

            String query = server.only().query();
            assertThat(query).contains("page=0").contains("size=5");
            assertThat(query).doesNotContain("query=").doesNotContain("city=").doesNotContain("category=");
        }

        @Test
        @DisplayName("tham số có giá trị được cắt khoảng trắng và mã hoá URL")
        void tham_so_duoc_cat_va_ma_hoa() {
            server.respond(200, "{\"items\":[" + card("a", "A") + "]}");

            adapter().searchEvents("  Đêm nhạc  ", " Hà Nội ", "nhac-song", 5);

            String query = server.only().query();
            assertThat(query).contains("query=%C4%90%C3%AAm%20nh%E1%BA%A1c");
            assertThat(query).contains("city=H%C3%A0%20N%E1%BB%99i").contains("category=nhac-song");
        }

        @Test
        @DisplayName("limit bị chặn trong [1, 8] dù mô hình xin bao nhiêu")
        void limit_bi_chan() {
            server.respond(200, "{\"items\":[" + card("a", "A") + "]}");
            CatalogHttpAdapter adapter = adapter();

            adapter.searchEvents("x", null, null, 1000);
            adapter.searchEvents("x", null, null, 0);
            adapter.searchEvents("x", null, null, -5);

            assertThat(server.received())
                    .extracting(StubHttpServer.Received::query)
                    .satisfiesExactly(
                            q -> assertThat(q).contains("size=8"),
                            q -> assertThat(q).contains("size=1"),
                            q -> assertThat(q).contains("size=1"));
        }

        @Test
        @DisplayName("không ra gì thì thử lại đúng một lần bằng từ dài nhất trong câu")
        void thu_lai_bang_tu_dai_nhat() {
            server.respondWith(request -> request.query().contains("query=Ng%C6%B0%E1%BB%9Di")
                    ? new StubHttpServer.Reply(
                            200, "{\"items\":[" + card("nguoi-o-lai", "Kịch nói — Người Ở Lại") + "]}")
                    : new StubHttpServer.Reply(200, EMPTY_PAGE));

            // "Kịch nói Người Ở Lại" không phải chuỗi con của "Kịch nói — Người Ở Lại"; từ dài nhất là "Người".
            List<EventBrief> found = adapter().searchEvents("Kịch nói Người Ở Lại", null, null, 5);

            assertThat(found).extracting(EventBrief::slug).containsExactly("nguoi-o-lai");
            assertThat(server.received()).hasSize(2);
            assertThat(server.received().get(0).query()).contains("query=K%E1%BB%8Bch%20n%C3%B3i");
        }

        @Test
        @DisplayName("lần thử lại giữ nguyên city và category")
        void thu_lai_giu_bo_loc() {
            server.respond(200, EMPTY_PAGE);

            adapter().searchEvents("Kịch nói Người Ở Lại", "Hà Nội", "kich", 5);

            assertThat(server.received()).hasSize(2);
            assertThat(server.received().get(1).query()).contains("city=").contains("category=kich");
        }

        @Test
        @DisplayName("từ khoá một chữ hoặc toàn từ ngắn thì không thử lại — lần hai cho cùng kết quả rỗng")
        void khong_thu_lai_khi_khong_ich() {
            server.respond(200, EMPTY_PAGE);
            CatalogHttpAdapter adapter = adapter();

            assertThat(adapter.searchEvents("Trịnh", null, null, 5)).isEmpty();
            assertThat(adapter.searchEvents("và ở đó", null, null, 5)).isEmpty();
            assertThat(adapter.searchEvents(null, null, null, 5)).isEmpty();
            assertThat(adapter.searchEvents("   ", null, null, 5)).isEmpty();

            assertThat(server.received()).hasSize(4);
        }

        @Test
        @DisplayName("body rỗng hoặc không có items thì là danh sách rỗng, không ném")
        void body_rong_la_danh_sach_rong() {
            for (String body : new String[] {"{}", "", "{\"items\":null}"}) {
                try (StubHttpServer local = new StubHttpServer()) {
                    local.respond(200, body);

                    assertThat(adapterAt(local.baseUrl()).searchEvents("Trịnh", null, null, 5))
                            .as("body=[%s]", body)
                            .isEmpty();
                }
            }
        }

        @Test
        @DisplayName("non-2xx thành RemoteCallException có tên service, và không thử lại")
        void non_2xx_thanh_remote_call_exception() {
            for (int status : new int[] {400, 404, 500, 503}) {
                try (StubHttpServer local = new StubHttpServer()) {
                    local.respond(status, "{}");

                    assertThatThrownBy(() -> adapterAt(local.baseUrl()).searchEvents("Trịnh", null, null, 5))
                            .as("HTTP %d", status)
                            .isInstanceOfSatisfying(RemoteCallException.class, e -> {
                                assertThat(e.service()).isEqualTo("catalog-service");
                                assertThat(e.getMessage()).contains(String.valueOf(status));
                            });
                    assertThat(local.received()).hasSize(1);
                }
            }
        }

        @Test
        @DisplayName("mạng đứt hoặc JSON hỏng thành RemoteCallException mang nguyên nhân gốc")
        void mang_dut_va_json_hong() {
            assertThatThrownBy(() -> adapterAt(StubHttpServer.deadBaseUrl()).searchEvents("Trịnh", null, null, 5))
                    .isInstanceOfSatisfying(RemoteCallException.class, e -> {
                        assertThat(e.service()).isEqualTo("catalog-service");
                        assertThat(e.getCause()).isNotNull();
                    });

            server.respond(200, "<html>gateway</html>");
            assertThatThrownBy(() -> adapter().searchEvents("Trịnh", null, null, 5))
                    .isInstanceOf(RemoteCallException.class);
        }
    }

    @Nested
    @DisplayName("findEventBySlug")
    class FindEventBySlug {

        private static final String DETAIL =
                """
                {"slug":"dem-nhac-trinh","title":"Đêm nhạc Trịnh","summary":"tóm tắt","description":"mô tả",
                 "category":"nhac-song","city":"Hà Nội","venueName":"Nhà hát Lớn","venueAddress":"1 Tràng Tiền",
                 "posterUrl":"https://x/p.png","floorPlan":{"zones":[]},
                 "sessions":[{"id":"%s","startsAt":"2026-11-20T13:00:00Z","endsAt":"2026-11-20T15:00:00Z",
                              "salesCloseAt":"2026-11-20T12:00:00Z",
                              "tiers":[{"name":"VIP","priceVnd":1800000,"zoneCode":"A","zoneName":"Khán đài A"},
                                       {"name":"Phổ thông","priceVnd":450000,"zoneCode":"GA","zoneName":"Khu đứng"}]}]}
                """;

        @Test
        @DisplayName("ánh xạ đủ trường, suất và hạng vé")
        void anh_xa_day_du() {
            UUID sessionId = UUID.randomUUID();
            server.respond(200, DETAIL.formatted(sessionId));

            Optional<EventDetail> found = adapter().findEventBySlug("dem-nhac-trinh");

            assertThat(server.only().path()).isEqualTo("/v1/events/dem-nhac-trinh");
            EventDetail detail = found.orElseThrow();
            assertThat(detail.slug()).isEqualTo("dem-nhac-trinh");
            assertThat(detail.summary()).isEqualTo("tóm tắt");
            assertThat(detail.description()).isEqualTo("mô tả");
            assertThat(detail.venueAddress()).isEqualTo("1 Tràng Tiền");
            assertThat(detail.sessions()).hasSize(1);
            EventDetail.Session session = detail.sessions().get(0);
            assertThat(session.id()).isEqualTo(sessionId);
            assertThat(session.startsAt()).isEqualTo(Instant.parse("2026-11-20T13:00:00Z"));
            assertThat(session.salesCloseAt()).isEqualTo(Instant.parse("2026-11-20T12:00:00Z"));
            assertThat(session.tiers())
                    .extracting(EventDetail.Tier::zoneCode, EventDetail.Tier::priceVnd, EventDetail.Tier::zoneName)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("A", 1_800_000L, "Khán đài A"),
                            org.assertj.core.groups.Tuple.tuple("GA", 450_000L, "Khu đứng"));
        }

        @Test
        @DisplayName("sự kiện không có suất hoặc suất không có hạng vé thì là danh sách rỗng, không NPE")
        void khong_co_suat_hoac_hang_ve() {
            server.respond(200, "{\"slug\":\"a\",\"title\":\"A\"}");
            assertThat(adapter().findEventBySlug("a").orElseThrow().sessions()).isEmpty();

            try (StubHttpServer local = new StubHttpServer()) {
                local.respond(
                        200, "{\"slug\":\"a\",\"title\":\"A\",\"sessions\":[{\"id\":\"" + UUID.randomUUID() + "\"}]}");
                EventDetail detail =
                        adapterAt(local.baseUrl()).findEventBySlug("a").orElseThrow();
                assertThat(detail.sessions()).hasSize(1);
                assertThat(detail.sessions().get(0).tiers()).isEmpty();
            }
        }

        @Test
        @DisplayName("404 là đường đi bình thường (mô hình đoán sai slug): trả rỗng, không ném")
        void khong_co_slug_la_rong() {
            server.respond(404, "{\"code\":\"NOT_FOUND\"}");

            assertThat(adapter().findEventBySlug("slug-tu-bia")).isEmpty();
        }

        @Test
        @DisplayName("body rỗng ở 200 thì là rỗng")
        void body_rong_la_rong() {
            server.respond(200, "");

            assertThat(adapter().findEventBySlug("a")).isEmpty();
        }

        @Test
        @DisplayName("5xx và 401 là RemoteCallException, KHÁC với 404")
        void loi_ha_tang() {
            for (int status : new int[] {401, 500, 502}) {
                try (StubHttpServer local = new StubHttpServer()) {
                    local.respond(status, "{}");

                    assertThatThrownBy(() -> adapterAt(local.baseUrl()).findEventBySlug("a"))
                            .as("HTTP %d", status)
                            .isInstanceOfSatisfying(RemoteCallException.class, e -> assertThat(e.service())
                                    .isEqualTo("catalog-service"));
                }
            }
        }

        @Test
        @DisplayName("slug có ký tự đặc biệt được mã hoá trong đường dẫn, không thoát ra khỏi /v1/events/")
        void slug_duoc_ma_hoa() {
            server.respond(404, "{}");

            adapter().findEventBySlug("../admin x");

            assertThat(server.only().path()).isEqualTo("/v1/events/..%2Fadmin%20x");
        }

        @Test
        @DisplayName("mạng đứt là RemoteCallException")
        void mang_dut() {
            assertThatThrownBy(() -> adapterAt(StubHttpServer.deadBaseUrl()).findEventBySlug("a"))
                    .isInstanceOf(RemoteCallException.class)
                    .hasCauseInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("findZoneAdmission")
    class FindZoneAdmission {

        private static final String PLAN =
                "{\"zones\":[{\"zoneCode\":\"A\",\"kind\":\"SEATED\"},{\"zoneCode\":\"ga\",\"kind\":\"standing\"},"
                        + "{\"zoneCode\":\"X\",\"kind\":\"BOX\"},{\"kind\":\"SEATED\"}],\"polygon\":[1,2]}";

        @Test
        @DisplayName("đọc loại khu từ sơ đồ công khai, không phân biệt hoa thường ở cả mã khu lẫn kind")
        void doc_loai_khu() {
            server.respond(200, PLAN);
            CatalogHttpAdapter adapter = adapter();

            assertThat(adapter.findZoneAdmission("dem", "A")).contains(ZoneAdmission.SEATED);
            assertThat(adapter.findZoneAdmission("dem", "GA")).contains(ZoneAdmission.STANDING);
            assertThat(adapter.findZoneAdmission("dem", "a")).contains(ZoneAdmission.SEATED);
            assertThat(server.received().get(0).path()).isEqualTo("/v1/events/dem/floor-plan");
        }

        @Test
        @DisplayName("kind lạ hoặc khu không có trong sơ đồ thì rỗng — tool giữ chỗ sẽ mời khách sang web")
        void kind_la_hoac_khu_la() {
            server.respond(200, PLAN);
            CatalogHttpAdapter adapter = adapter();

            assertThat(adapter.findZoneAdmission("dem", "X")).isEmpty();
            assertThat(adapter.findZoneAdmission("dem", "ZZ")).isEmpty();
        }

        @Test
        @DisplayName("sơ đồ không có zones hoặc body rỗng thì rỗng")
        void khong_co_zones() {
            server.respond(200, "{}");
            assertThat(adapter().findZoneAdmission("dem", "A")).isEmpty();

            try (StubHttpServer local = new StubHttpServer()) {
                local.respond(200, "");
                assertThat(adapterAt(local.baseUrl()).findZoneAdmission("dem", "A"))
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("404 là rỗng, còn 5xx và mạng đứt là RemoteCallException")
        void ma_loi() {
            server.respond(404, "{}");
            assertThat(adapter().findZoneAdmission("dem", "A")).isEmpty();

            try (StubHttpServer local = new StubHttpServer()) {
                local.respond(500, "{}");
                assertThatThrownBy(() -> adapterAt(local.baseUrl()).findZoneAdmission("dem", "A"))
                        .isInstanceOf(RemoteCallException.class);
            }
            assertThatThrownBy(() -> adapterAt(StubHttpServer.deadBaseUrl()).findZoneAdmission("dem", "A"))
                    .isInstanceOf(RemoteCallException.class)
                    .hasCauseInstanceOf(RuntimeException.class);
        }
    }
}
