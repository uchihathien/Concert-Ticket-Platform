// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.interfaces.rest;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.query.CatalogQueries;
import com.nexaticket.catalog.application.query.CatalogViews;
import com.nexaticket.catalog.application.query.FloorPlanQuery;
import com.nexaticket.catalog.application.query.FloorPlanViews;
import com.nexaticket.catalog.application.query.TrendingEventsQuery;
import com.nexaticket.catalog.support.WebSliceBase;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Catalog công khai: không cần đăng nhập, chỉ thấy sự kiện đang bán (điều kiện nằm trong SQL — xem
 * {@code JdbcCatalogQueries} và IT tương ứng), phân trang bị kẹp, tham số hỏng là 400.
 */
@WebMvcTest(controllers = PublicCatalogController.class)
class PublicCatalogControllerTest extends WebSliceBase {

    @MockitoBean
    CatalogQueries queries;

    @MockitoBean
    FloorPlanQuery floorPlans;

    @MockitoBean
    TrendingEventsQuery trending;

    private static CatalogViews.EventCard card(String slug) {
        return new CatalogViews.EventCard(
                slug,
                "Đêm nhạc",
                null,
                "nhac-song",
                null,
                "Hà Nội",
                "Nhà hát",
                Instant.parse("2026-12-01T12:00:00Z"),
                500_000L,
                1);
    }

    @Nested
    @DisplayName("Danh sách")
    class DanhSach {

        @Test
        @DisplayName("không cần đăng nhập; mặc định trang 0, 20 mục; cache công khai 2 phút")
        void mac_dinh() throws Exception {
            when(queries.publishedEvents(any(), eq(20), eq(0))).thenReturn(List.of(card("a")));
            when(queries.countPublishedEvents(any())).thenReturn(1);
            when(queries.citiesWithPublishedEvents()).thenReturn(List.of("Hà Nội"));

            mvc.perform(get("/v1/events"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", containsString("max-age=120")))
                    .andExpect(header().string("Cache-Control", containsString("public")))
                    .andExpect(jsonPath("$.items[0].slug").value("a"))
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.cities[0]").value("Hà Nội"));
        }

        @ParameterizedTest(name = "size={0} → {1}")
        @CsvSource({"1000,60", "61,60", "60,60", "1,1", "0,1", "-5,1", "20,20"})
        @DisplayName("size bị kẹp vào 1..60")
        void kep_size(int requested, int expected) throws Exception {
            when(queries.publishedEvents(any(), anyInt(), anyInt())).thenReturn(List.of());

            mvc.perform(get("/v1/events").param("size", Integer.toString(requested)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(expected));

            verify(queries).publishedEvents(any(), eq(expected), eq(0));
        }

        @Test
        @DisplayName("page âm coi như trang 0; page=3,size=10 → offset 30")
        void phan_trang() throws Exception {
            when(queries.publishedEvents(any(), anyInt(), anyInt())).thenReturn(List.of());

            mvc.perform(get("/v1/events").param("page", "-7"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(0));
            verify(queries).publishedEvents(any(), eq(20), eq(0));

            mvc.perform(get("/v1/events").param("page", "3").param("size", "10"))
                    .andExpect(status().isOk());
            verify(queries).publishedEvents(any(), eq(10), eq(30));
        }

        @Test
        @DisplayName("page cực lớn không tràn số thành offset âm")
        void page_cuc_lon() throws Exception {
            when(queries.publishedEvents(any(), anyInt(), anyInt())).thenReturn(List.of());

            mvc.perform(get("/v1/events")
                            .param("page", Integer.toString(Integer.MAX_VALUE))
                            .param("size", "60"))
                    .andExpect(status().isOk());

            ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
            verify(queries).publishedEvents(any(), eq(60), offset.capture());
            org.assertj.core.api.Assertions.assertThat(offset.getValue())
                    .as("offset = page × size không được tràn int")
                    .isNotNegative();
        }

        @Test
        @DisplayName("bộ lọc được chuyển nguyên xuống truy vấn, mốc thời gian đọc theo ISO-8601")
        void bo_loc() throws Exception {
            when(queries.publishedEvents(any(), anyInt(), anyInt())).thenReturn(List.of());

            mvc.perform(get("/v1/events")
                            .param("query", "hoà âm")
                            .param("city", "Hà Nội")
                            .param("category", "nhac-song")
                            .param("from", "2026-12-01T00:00:00+07:00")
                            .param("to", "2026-12-08T00:00:00Z")
                            .param("minPrice", "0")
                            .param("maxPrice", "500000"))
                    .andExpect(status().isOk());

            ArgumentCaptor<CatalogQueries.EventFilter> filter =
                    ArgumentCaptor.forClass(CatalogQueries.EventFilter.class);
            verify(queries).publishedEvents(filter.capture(), eq(20), eq(0));
            org.assertj.core.api.Assertions.assertThat(filter.getValue())
                    .isEqualTo(new CatalogQueries.EventFilter(
                            "hoà âm",
                            "Hà Nội",
                            "nhac-song",
                            Instant.parse("2026-11-30T17:00:00Z"),
                            Instant.parse("2026-12-08T00:00:00Z"),
                            0L,
                            500_000L));
        }

        @ParameterizedTest(name = "{0}={1}")
        @CsvSource({
            "size,abc",
            "page,x",
            "page,1.5",
            "from,mai",
            "to,2026-13-45",
            "minPrice,-1",
            "maxPrice,-1",
            "minPrice,rat-re",
            "maxPrice,99999999999999999999"
        })
        @DisplayName("tham số sai kiểu hoặc âm: 400, không phải 500, không chạm truy vấn")
        void tham_so_hong(String name, String value) throws Exception {
            mvc.perform(get("/v1/events").param(name, value))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            verifyNoInteractions(queries);
        }

        @Test
        @DisplayName("minPrice > maxPrice: không 5xx, trả trang rỗng hợp lệ")
        void gia_nguoc() throws Exception {
            when(queries.publishedEvents(any(), anyInt(), anyInt())).thenReturn(List.of());

            mvc.perform(get("/v1/events").param("minPrice", "900000").param("maxPrice", "100000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").isEmpty());
        }

        @Test
        @DisplayName("chuỗi tìm kiếm có ký tự đặc biệt (%, _, ', <script>) đi qua nguyên văn, không lỗi")
        void tim_kiem_ky_tu_dac_biet() throws Exception {
            when(queries.publishedEvents(any(), anyInt(), anyInt())).thenReturn(List.of());

            mvc.perform(get("/v1/events").param("query", "100%_' OR 1=1 --<script>"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", startsWith("application/json")));
        }
    }

    @Nested
    @DisplayName("Bán chạy")
    class BanChay {

        @Test
        @DisplayName("mặc định 8 mục, cache 5 phút; /trending không bị nhầm với slug")
        void mac_dinh() throws Exception {
            when(trending.handle(8)).thenReturn(List.of(card("hot")));

            mvc.perform(get("/v1/events/trending"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", containsString("max-age=300")))
                    .andExpect(jsonPath("$[0].slug").value("hot"));
            verifyNoInteractions(queries);
        }

        @ParameterizedTest(name = "size={0} → {1}")
        @CsvSource({"500,60", "0,1", "-3,1", "12,12"})
        @DisplayName("size bị kẹp vào 1..60")
        void kep_size(int requested, int expected) throws Exception {
            when(trending.handle(anyInt())).thenReturn(List.of());

            mvc.perform(get("/v1/events/trending").param("size", Integer.toString(requested)))
                    .andExpect(status().isOk());

            verify(trending).handle(expected);
        }

        @Test
        @DisplayName("size không phải số: 400")
        void size_hong() throws Exception {
            mvc.perform(get("/v1/events/trending").param("size", "nhieu")).andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Chi tiết và mặt bằng")
    class ChiTiet {

        @Test
        @DisplayName("slug không có (hoặc sự kiện chưa publish): 404 EVENT_NOT_FOUND dạng RFC 7807")
        void khong_co() throws Exception {
            when(queries.publishedEventBySlug("khong-co")).thenReturn(Optional.empty());

            mvc.perform(get("/v1/events/khong-co"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.type").value("https://nexaticket.vn/errors/event-not-found"));
        }

        @ParameterizedTest(name = "#{index}")
        @ValueSource(
                strings = {"%3Cscript%3Ealert(1)%3C%2Fscript%3E", "'%20OR%201=1--", "..%2F..%2Fetc%2Fpasswd", "a%00b"})
        @DisplayName(
                "slug mang ký tự nguy hiểm: bị từ chối 4xx (firewall hoặc 404), không 5xx, không phản chiếu đầu vào")
        void slug_nguy_hiem(String slug) throws Exception {
            when(queries.publishedEventBySlug(any())).thenReturn(Optional.empty());

            mvc.perform(get(java.net.URI.create("/v1/events/" + slug)))
                    .andExpect(status().is4xxClientError())
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<script>"))));
        }

        @Test
        @DisplayName("sự kiện có tiêu đề chứa HTML: trả JSON nguyên văn, kiểu nội dung JSON, kèm nosniff")
        void noi_dung_html_khong_thanh_trang_html() throws Exception {
            CatalogViews.EventDetail detail = new CatalogViews.EventDetail(
                    "xss",
                    "<script>alert(1)</script>",
                    null,
                    "<img src=x onerror=alert(1)>",
                    "c",
                    null,
                    "HN",
                    "V",
                    null,
                    null,
                    List.of());
            when(queries.publishedEventBySlug("xss")).thenReturn(Optional.of(detail));

            mvc.perform(get("/v1/events/xss"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", startsWith("application/json")))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(jsonPath("$.title").value("<script>alert(1)</script>"))
                    .andExpect(header().string("Cache-Control", containsString("max-age=120")));
        }

        @Test
        @DisplayName("mặt bằng của sự kiện không công khai: 404; có thì cache 10 phút")
        void mat_bang() throws Exception {
            when(floorPlans.forPublishedEvent("an"))
                    .thenThrow(new ApiException(CatalogErrorCode.EVENT_NOT_FOUND, "Event not found"));
            when(floorPlans.forPublishedEvent("hien"))
                    .thenReturn(new FloorPlanViews.FloorPlanView(
                            java.util.UUID.randomUUID(),
                            "V",
                            new FloorPlanViews.Stage("RECTANGLE", 0, 0, 10, 2),
                            List.of(),
                            new FloorPlanViews.Bounds(0, 0, 1, 1)));

            mvc.perform(get("/v1/events/an/floor-plan")).andExpect(status().isNotFound());
            mvc.perform(get("/v1/events/hien/floor-plan"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", containsString("max-age=600")));
        }

        @Test
        @DisplayName("chỉ đọc: POST/DELETE lên danh sách công khai không được 2xx")
        void chi_doc() throws Exception {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/events"))
                    .andExpect(status().is4xxClientError());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/v1/events/a"))
                    .andExpect(status().is4xxClientError());
        }
    }
}
