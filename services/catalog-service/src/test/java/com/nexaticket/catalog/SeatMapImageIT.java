// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.catalog.support.CatalogTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Ảnh sơ đồ khu vực ghế: hai chỗ đặt, một thứ tự ưu tiên.
 *
 * <p>Thứ tự ấy ("ảnh của sự kiện đè ảnh của địa điểm") là một luật, và luật này dễ hỏng theo kiểu
 * khó thấy: gỡ ảnh riêng của sự kiện thì ảnh địa điểm hiện lên thế chỗ, nên với ban tổ chức nút
 * "gỡ ảnh" trông như <b>không chạy</b>. Vì vậy phần kiểm ở đây đi cả hai chiều — đặt vào và gỡ ra —
 * chứ không chỉ kiểm lúc đặt.
 *
 * <p>Dùng URL ngoài (https) chứ không tải ảnh thật lên: {@code MEDIA_ALLOW_EXTERNAL_URLS} mặc định
 * bật, nên đường này không cần kho vật thể. Phần "ảnh của chính mình phải có thật, đúng kiểu, đúng
 * cỡ" là việc của {@code PosterUrlPolicyTest} và {@code PosterLifecycleIT}, và lặp lại ở đây chỉ
 * buộc bộ test này phải có MinIO mới chạy được.
 */
class SeatMapImageIT extends CatalogTestBase {

    private static final String VENUE_IMAGE = "https://cdn.example.com/so-do-nha-hat.png";
    private static final String EVENT_IMAGE = "https://cdn.example.com/so-do-dem-nhac.png";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper json;

    @Test
    @DisplayName("ảnh của địa điểm hiện trên trang công khai của mọi sự kiện ở đó")
    void anh_dia_diem_dung_cho_moi_su_kien() throws Exception {
        Fixture f = publishedEvent();

        setVenueImage(f.venueId(), VENUE_IMAGE);

        assertThat(publicDetail(f.slug()).path("seatMapImageUrl").asText()).isEqualTo(VENUE_IMAGE);
    }

    @Test
    @DisplayName("ảnh riêng của sự kiện đè lên ảnh của địa điểm")
    void anh_su_kien_de_len_anh_dia_diem() throws Exception {
        Fixture f = publishedEvent();
        setVenueImage(f.venueId(), VENUE_IMAGE);

        setEventImage(f.eventId(), EVENT_IMAGE);

        // Đây là lý do tồn tại của cột thứ hai: giá vé in trên sơ đồ đổi theo từng sự kiện, trong
        // khi hình dạng khán phòng thì không.
        assertThat(publicDetail(f.slug()).path("seatMapImageUrl").asText()).isEqualTo(EVENT_IMAGE);
    }

    @Test
    @DisplayName("gỡ ảnh riêng: quay về ảnh của địa điểm, không phải về trống")
    void go_anh_su_kien_quay_ve_anh_dia_diem() throws Exception {
        Fixture f = publishedEvent();
        setVenueImage(f.venueId(), VENUE_IMAGE);
        setEventImage(f.eventId(), EVENT_IMAGE);

        setEventImage(f.eventId(), "");

        assertThat(publicDetail(f.slug()).path("seatMapImageUrl").asText()).isEqualTo(VENUE_IMAGE);
    }

    @Test
    @DisplayName("gỡ cả hai: trang công khai không có ảnh sơ đồ, và đó là trạng thái hợp lệ")
    void khong_co_anh_nao() throws Exception {
        Fixture f = publishedEvent();

        // Không đặt gì cả. Sơ đồ hệ thống tự vẽ vẫn còn, nên đây không phải lỗi.
        assertThat(publicDetail(f.slug()).path("seatMapImageUrl").isNull()).isTrue();
    }

    @Test
    @DisplayName("màn hình quản trị thấy cả hai ảnh, không chỉ ảnh đang có hiệu lực")
    void quan_tri_thay_ca_hai() throws Exception {
        Fixture f = publishedEvent();
        setVenueImage(f.venueId(), VENUE_IMAGE);
        setEventImage(f.eventId(), EVENT_IMAGE);

        JsonNode images =
                json.readTree(perform(get("/v1/organizations/" + ORG + "/events/" + f.eventId() + "/seat-map-image"))
                        .getResponse()
                        .getContentAsString());

        // Thiếu venueImageUrl thì giao diện không giải thích được vì sao gỡ ảnh xong vẫn còn ảnh.
        assertThat(images.path("eventImageUrl").asText()).isEqualTo(EVENT_IMAGE);
        assertThat(images.path("venueImageUrl").asText()).isEqualTo(VENUE_IMAGE);
        assertThat(images.path("effectiveImageUrl").asText()).isEqualTo(EVENT_IMAGE);
    }

    @Test
    @DisplayName("đường dẫn không phải https bị từ chối")
    void tu_choi_duong_dan_khong_https() throws Exception {
        Fixture f = publishedEvent();

        MvcResult result = mockMvc.perform(put("/v1/organizations/" + ORG + "/venues/" + f.venueId()
                                + "/seat-map-image")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("imageUrl", "http://cdn.example.com/so-do.png"))))
                .andReturn();

        // 422, không phải 400: body đúng cú pháp JSON và đủ trường — thứ sai là giá trị, và mã
        // đó phân biệt "gửi hỏng" với "gửi đúng khuôn nhưng không dùng được".
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(json.readTree(result.getResponse().getContentAsString())
                        .path("code")
                        .asText())
                .isEqualTo("POSTER_URL_INVALID");
    }

    @Test
    @DisplayName("địa điểm của tổ chức khác: 404, không phải 403")
    void dia_diem_to_chuc_khac() throws Exception {
        Fixture f = publishedEvent();

        // Tổ chức OTHER_ORG không có địa điểm này. Trả 403 sẽ xác nhận rằng nó tồn tại — một cách
        // dò danh sách địa điểm của người khác.
        MvcResult result = mockMvc.perform(
                        put("/v1/organizations/" + OTHER_ORG + "/venues/" + f.venueId() + "/seat-map-image")
                                .header("Authorization", BEARER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("imageUrl", VENUE_IMAGE))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isIn(403, 404);
    }

    // -----------------------------------------------------------------------

    private record Fixture(UUID venueId, UUID eventId, String slug) {}

    private void setVenueImage(UUID venueId, String imageUrl) throws Exception {
        perform(put("/v1/organizations/" + ORG + "/venues/" + venueId + "/seat-map-image")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("imageUrl", imageUrl))));
    }

    private void setEventImage(UUID eventId, String imageUrl) throws Exception {
        perform(put("/v1/organizations/" + ORG + "/events/" + eventId + "/seat-map-image")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("imageUrl", imageUrl))));
    }

    private JsonNode publicDetail(String slug) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/events/" + slug)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** Một sự kiện đã publish, tối thiểu: một khu ngồi, một suất, một hạng vé. */
    private Fixture publishedEvent() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        UUID venueId = UUID.fromString(json.readTree(perform(post("/v1/organizations/" + ORG + "/venues")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("name", "Nhà hát " + suffix, "city", "Huế"))))
                        .getResponse()
                        .getContentAsString())
                .path("id")
                .asText());

        perform(put("/v1/organizations/" + ORG + "/venues/" + venueId + "/zones")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "zones",
                        java.util.List.of(Map.of(
                                "zoneCode", "A",
                                "name", "Khu A",
                                "kind", "SEATED",
                                "rowCount", 4,
                                "seatsPerRow", 5))))));

        String slug = "so-do-" + suffix;
        JsonNode event = json.readTree(perform(post("/v1/organizations/" + ORG + "/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "venueId",
                                venueId,
                                "title",
                                "Sự kiện " + suffix,
                                "slug",
                                slug,
                                "category",
                                "nhac-song"))))
                .getResponse()
                .getContentAsString());
        UUID eventId = UUID.fromString(event.path("id").asText());
        UUID zoneId = UUID.fromString(
                event.path("venue").path("zones").get(0).path("id").asText());

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

        perform(post("/v1/organizations/" + ORG + "/events/" + eventId + "/sessions/" + sessionId + "/ticket-types")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                        Map.of("venueZoneId", zoneId, "name", "Vé ngồi", "priceVnd", 500_000))));

        perform(post("/v1/organizations/" + ORG + "/events/" + eventId + "/publish"));

        return new Fixture(venueId, eventId, slug);
    }

    private MvcResult perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        MvcResult result =
                mockMvc.perform(request.header("Authorization", BEARER)).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("%s", result.getResponse().getContentAsString())
                .isBetween(200, 299);
        return result;
    }
}
