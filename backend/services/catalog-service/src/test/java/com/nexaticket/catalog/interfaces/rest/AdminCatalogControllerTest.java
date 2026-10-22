// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.interfaces.rest;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.command.AddSessionHandler;
import com.nexaticket.catalog.application.command.AddTicketTypeHandler;
import com.nexaticket.catalog.application.command.AddZoneHandler;
import com.nexaticket.catalog.application.command.CancelEventHandler;
import com.nexaticket.catalog.application.command.CreateEventHandler;
import com.nexaticket.catalog.application.command.CreateVenueHandler;
import com.nexaticket.catalog.application.command.EditSessionHandler;
import com.nexaticket.catalog.application.command.EditTicketTypeHandler;
import com.nexaticket.catalog.application.command.PublishEventHandler;
import com.nexaticket.catalog.application.command.UnpublishEventHandler;
import com.nexaticket.catalog.application.command.UpdateEventHandler;
import com.nexaticket.catalog.application.query.AdminCatalogQuery;
import com.nexaticket.catalog.application.query.CatalogQueries;
import com.nexaticket.catalog.application.query.CatalogViews;
import com.nexaticket.catalog.support.WebSliceBase;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Khu vực quản trị của ban tổ chức, qua chuỗi bảo mật THẬT của nền tảng.
 *
 * <p>{@link CatalogAccess}, {@link AdminCatalogQuery} và {@link CreateVenueHandler} là bản thật (chỉ
 * kho dữ liệu là mock) để kiểm được cửa vai trò ở đường đọc lẫn đường ghi; các lệnh còn lại là mock
 * vì quy tắc của chúng đã có test riêng ở tầng application. Ở đây chỉ chứng minh ánh xạ HTTP: mã
 * trạng thái, kiểm tra đầu vào, cách ly tổ chức, hình dạng lỗi RFC 7807.
 */
@WebMvcTest(controllers = AdminCatalogController.class)
class AdminCatalogControllerTest extends WebSliceBase {

    private static final UUID ORG = UUID.fromString("11111111-1111-4111-a111-111111111111");
    private static final UUID OTHER_ORG = UUID.fromString("22222222-2222-4222-a222-222222222222");
    private static final UUID EVENT = UUID.fromString("33333333-3333-4333-a333-333333333333");
    private static final UUID SESSION = UUID.fromString("44444444-4444-4444-a444-444444444444");
    private static final UUID ZONE = UUID.fromString("55555555-5555-4555-a555-555555555555");
    private static final String BASE = "/v1/organizations/" + ORG;

    @MockitoBean
    AdminCatalogQuery adminQuery;

    @MockitoBean
    CreateVenueHandler createVenue;

    @MockitoBean
    AddZoneHandler addZone;

    @MockitoBean
    CreateEventHandler createEvent;

    @MockitoBean
    UpdateEventHandler updateEvent;

    @MockitoBean
    AddSessionHandler addSession;

    @MockitoBean
    AddTicketTypeHandler addTicketType;

    @MockitoBean
    PublishEventHandler publishEvent;

    @MockitoBean
    UnpublishEventHandler unpublishEvent;

    @MockitoBean
    EditSessionHandler editSession;

    @MockitoBean
    EditTicketTypeHandler editTicketType;

    @MockitoBean
    CancelEventHandler cancelEvent;

    @MockitoBean
    CatalogQueries queries;

    private String manager;

    @BeforeEach
    void dangNhapVaDungSuKien() {
        manager = bearer(tokenFor(UUID.randomUUID(), Map.of(TenantId.of(ORG), Role.EVENT_MANAGER), false));
        when(adminQuery.event(ORG, EVENT))
                .thenReturn(Optional.of(new CatalogViews.AdminEventDetail(
                        EVENT,
                        "dem-nhac",
                        "Đêm nhạc",
                        null,
                        null,
                        "nhac-song",
                        null,
                        "DRAFT",
                        null,
                        null,
                        List.of(),
                        List.of())));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.header("Authorization", manager)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Nested
    @DisplayName("Xác thực và cách ly tổ chức")
    class XacThuc {

        @Test
        @DisplayName("không token: 401, không chạm kho")
        void khong_token() throws Exception {
            mvc.perform(get(BASE + "/venues")).andExpect(status().isUnauthorized());
            verifyNoInteractions(createVenue);
        }

        @Test
        @DisplayName("token rác: 401")
        void token_rac() throws Exception {
            mvc.perform(get(BASE + "/venues").header("Authorization", "Bearer rac"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("tổ chức của người khác: 404 chứ không 403, mọi phương thức, không chạm kho")
        void to_chuc_khac() throws Exception {
            String other = "/v1/organizations/" + OTHER_ORG;

            mvc.perform(get(other + "/venues").header("Authorization", manager)).andExpect(status().isNotFound());
            mvc.perform(post(other + "/events/" + EVENT + "/publish").header("Authorization", manager))
                    .andExpect(status().isNotFound());
            mvc.perform(delete(other + "/events/" + EVENT).header("Authorization", manager))
                    .andExpect(status().isNotFound());
            verifyNoInteractions(adminQuery, publishEvent, cancelEvent);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
                strings = {"not-a-uuid", "%31111111-1111-4111-a111-111111111111", "11111111-1111-4111-a111-11111111111"
                })
        @DisplayName("id tổ chức sai hình dạng hoặc mã hoá phần trăm: 404, không lọt qua bộ lọc")
        void id_to_chuc_sai(String segment) throws Exception {
            mvc.perform(get(java.net.URI.create("/v1/organizations/" + segment + "/venues"))
                            .header("Authorization", manager))
                    .andExpect(status().isNotFound());
            verifyNoInteractions(createVenue);
        }

        @Test
        @DisplayName("khách không thuộc tổ chức nào: 404")
        void khach() throws Exception {
            mvc.perform(get(BASE + "/venues").header("Authorization", bearer(customerToken(UUID.randomUUID()))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("superadmin không thuộc tổ chức nào nên cũng nhận 404 ở /organizations/{id} — theo thiết kế")
        void superadmin() throws Exception {
            String admin = bearer(tokenFor(UUID.randomUUID(), Map.of(), true));

            mvc.perform(get(BASE + "/venues").header("Authorization", admin)).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("Địa điểm và khu")
    class DiaDiem {

        @Test
        @DisplayName("tạo địa điểm hợp lệ: 201, JSON, nosniff, và nội dung HTML được trả nguyên văn (không escape)")
        void tao() throws Exception {
            UUID id = UUID.randomUUID();
            when(createVenue.handle(ORG, "Nhà hát <b>Lớn</b>", "Hà Nội", null)).thenReturn(id);
            when(adminQuery.venues(ORG))
                    .thenReturn(List.of(
                            new CatalogViews.AdminVenue(id, "Nhà hát <b>Lớn</b>", "Hà Nội", null, 0, null, List.of())));

            mvc.perform(json(post(BASE + "/venues"), "{\"name\":\"Nhà hát <b>Lớn</b>\",\"city\":\"Hà Nội\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Content-Type", startsWith("application/json")))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(jsonPath("$.name").value("Nhà hát <b>Lớn</b>"))
                    .andExpect(jsonPath("$.zones").isEmpty());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
                strings = {
                    "{}",
                    "{\"name\":\"\",\"city\":\"HN\"}",
                    "{\"name\":\"   \",\"city\":\"HN\"}",
                    "{\"name\":\"A\"}",
                    "{\"name\":\"A\",\"city\":\"\"}"
                })
        @DisplayName("thiếu hoặc trống tên/thành phố: 400 VALIDATION_FAILED kèm meta.fields")
        void thieu_truong(String body) throws Exception {
            mvc.perform(json(post(BASE + "/venues"), body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.meta.fields").isMap());
            verifyNoInteractions(createVenue);
        }

        @Test
        @DisplayName("tên dài 201 ký tự: 400 nêu đúng trường name")
        void ten_qua_dai() throws Exception {
            mvc.perform(json(post(BASE + "/venues"), "{\"name\":\"" + "a".repeat(201) + "\",\"city\":\"HN\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.meta.fields.name").exists());
        }

        @Test
        @DisplayName("JSON hỏng: 400, không lộ chi tiết của Jackson")
        void json_hong() throws Exception {
            mvc.perform(json(post(BASE + "/venues"), "{\"name\":"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.detail").value("Malformed request body"));
        }

        @Test
        @DisplayName("sai Content-Type: 415, không phải 500")
        void sai_content_type() throws Exception {
            mvc.perform(post(BASE + "/venues")
                            .header("Authorization", manager)
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("name=A"))
                    .andExpect(status().isUnsupportedMediaType());
        }

        @ParameterizedTest(name = "#{index}")
        @ValueSource(
                strings = {
                    "{\"zoneCode\":\"A\",\"name\":\"A\",\"kind\":\"BALCONY\",\"rowCount\":1,\"seatsPerRow\":1}",
                    "{\"zoneCode\":\"A\",\"name\":\"A\",\"kind\":\"SEATED\",\"rowCount\":0,\"seatsPerRow\":1}",
                    "{\"zoneCode\":\"A\",\"name\":\"A\",\"kind\":\"SEATED\",\"rowCount\":-1,\"seatsPerRow\":1}",
                    "{\"zoneCode\":\"A\",\"name\":\"A\",\"kind\":\"STANDING\",\"capacity\":0}",
                    "{\"zoneCode\":\"\",\"name\":\"A\",\"kind\":\"STANDING\",\"capacity\":5}",
                    "{\"zoneCode\":\"01234567890123456\",\"name\":\"A\",\"kind\":\"STANDING\",\"capacity\":5}",
                    "{\"zoneCode\":\"A\",\"name\":\"A\",\"kind\":\"STANDING\",\"capacity\":\"nhieu\"}",
                    "{\"zoneCode\":\"A\",\"name\":\"A\"}"
                })
        @DisplayName("khu sai loại / số âm / bằng 0 / mã quá dài / sai kiểu / thiếu kind: 400")
        void khu_sai(String body) throws Exception {
            mvc.perform(json(post(BASE + "/venues/" + UUID.randomUUID() + "/zones"), body))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(addZone);
        }

        @Test
        @DisplayName("id địa điểm không phải UUID: 400, không phải 500")
        void venue_id_sai() throws Exception {
            mvc.perform(json(
                            post(BASE + "/venues/abc/zones"),
                            "{\"zoneCode\":\"A\",\"name\":\"A\",\"kind\":\"STANDING\",\"capacity\":5}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
            verifyNoInteractions(addZone);
        }

        @Test
        @DisplayName("khu hợp lệ: 201 và sortOrder mặc định 0")
        void khu_hop_le() throws Exception {
            when(addZone.handle(eq(ORG), any(), eq("A"), eq("A"), eq("STANDING"), any(), any(), eq(5), eq(0)))
                    .thenReturn(new CatalogViews.AdminZone(ZONE, "A", "A", "STANDING", null, null, 5, 5, null));

            mvc.perform(json(
                            post(BASE + "/venues/" + UUID.randomUUID() + "/zones"),
                            "{\"zoneCode\":\"A\",\"name\":\"A\",\"kind\":\"STANDING\",\"capacity\":5}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.zoneCode").value("A"));
        }
    }

    @Nested
    @DisplayName("Sự kiện")
    class SuKien {

        private static final String VALID =
                "{\"venueId\":\"55555555-5555-4555-a555-555555555555\",\"title\":\"Đêm nhạc\",\"category\":\"nhac-song\"}";

        @Test
        @DisplayName("tạo hợp lệ: 201 kèm chi tiết bản nháp")
        void tao() throws Exception {
            when(createEvent.handle(eq(ORG), any(), eq("Đêm nhạc"), any(), any(), any(), eq("nhac-song"), any()))
                    .thenReturn(EVENT);

            mvc.perform(json(post(BASE + "/events"), VALID))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("DRAFT"))
                    .andExpect(jsonPath("$.slug").value("dem-nhac"));
        }

        @ParameterizedTest(name = "#{index}")
        @ValueSource(
                strings = {
                    "{\"title\":\"A\",\"category\":\"c\"}",
                    "{\"venueId\":\"55555555-5555-4555-a555-555555555555\",\"category\":\"c\"}",
                    "{\"venueId\":\"55555555-5555-4555-a555-555555555555\",\"title\":\"A\"}",
                    "{\"venueId\":\"not-uuid\",\"title\":\"A\",\"category\":\"c\"}",
                    "{\"venueId\":\"55555555-5555-4555-a555-555555555555\",\"title\":\"A\",\"category\":\"c\",\"slug\":\"%s\"}"
                })
        @DisplayName("thiếu venueId/tiêu đề/thể loại, venueId sai, slug > 96 ký tự: 400")
        void sai(String body) throws Exception {
            mvc.perform(json(post(BASE + "/events"), body.replace("%s", "a".repeat(97))))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(createEvent);
        }

        @Test
        @DisplayName("tiêu đề 201, tóm tắt 501, ảnh bìa 501 ký tự: 400 và nêu đúng từng trường")
        void qua_dai() throws Exception {
            String body =
                    "{\"venueId\":\"%s\",\"title\":\"%s\",\"summary\":\"%s\",\"category\":\"c\",\"posterUrl\":\"%s\"}"
                            .formatted(ZONE, "a".repeat(201), "a".repeat(501), "a".repeat(501));

            mvc.perform(json(post(BASE + "/events"), body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.meta.fields.title").exists())
                    .andExpect(jsonPath("$.meta.fields.summary").exists())
                    .andExpect(jsonPath("$.meta.fields.posterUrl").exists());
        }

        @Test
        @DisplayName("slug đã có: 409, thân lỗi RFC 7807 khớp mã HTTP")
        void slug_trung() throws Exception {
            when(createEvent.handle(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new ApiException(CatalogErrorCode.SLUG_ALREADY_TAKEN, "Slug đã có người dùng: x"));

            mvc.perform(json(post(BASE + "/events"), VALID))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://nexaticket.vn/errors/slug-already-taken"))
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.code").value("SLUG_ALREADY_TAKEN"))
                    .andExpect(jsonPath("$.detail").exists());
        }

        @Test
        @DisplayName("ảnh bìa bị chính sách từ chối: 422")
        void anh_bia_sai() throws Exception {
            when(createEvent.handle(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new ApiException(CatalogErrorCode.POSTER_URL_INVALID, "https"));

            mvc.perform(json(post(BASE + "/events"), VALID))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("POSTER_URL_INVALID"));
        }

        @Test
        @DisplayName("xem chi tiết sự kiện không có: 404 EVENT_NOT_FOUND")
        void khong_co() throws Exception {
            UUID missing = UUID.randomUUID();
            when(adminQuery.event(ORG, missing)).thenReturn(Optional.empty());

            mvc.perform(get(BASE + "/events/" + missing).header("Authorization", manager))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
        }

        @Test
        @DisplayName("id sự kiện không phải UUID: 400 trên mọi đường (GET, PATCH, DELETE, publish)")
        void id_sai() throws Exception {
            mvc.perform(get(BASE + "/events/abc").header("Authorization", manager))
                    .andExpect(status().isBadRequest());
            mvc.perform(json(patch(BASE + "/events/abc"), "{}")).andExpect(status().isBadRequest());
            mvc.perform(delete(BASE + "/events/abc").header("Authorization", manager))
                    .andExpect(status().isBadRequest());
            mvc.perform(post(BASE + "/events/abc/publish").header("Authorization", manager))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(publishEvent, cancelEvent, updateEvent);
        }

        @Test
        @DisplayName("phương thức sai: 405, không phải 500")
        void sai_phuong_thuc() throws Exception {
            mvc.perform(put(BASE + "/events/" + EVENT).header("Authorization", manager))
                    .andExpect(status().isMethodNotAllowed());
        }

        @Test
        @DisplayName("publish bị chặn: 409 PUBLISH_BLOCKED, meta.blockers là danh sách")
        void publish_bi_chan() throws Exception {
            doThrow(new ApiException(CatalogErrorCode.PUBLISH_BLOCKED, "x", Map.of("blockers", List.of("NO_SESSION"))))
                    .when(publishEvent)
                    .handle(ORG, EVENT);

            mvc.perform(post(BASE + "/events/" + EVENT + "/publish").header("Authorization", manager))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PUBLISH_BLOCKED"))
                    .andExpect(jsonPath("$.meta.blockers[0]").value("NO_SESSION"));
        }

        @Test
        @DisplayName("rút xuống khi không đang bán: 409 EVENT_NOT_PUBLISHED")
        void rut_xuong() throws Exception {
            doThrow(new ApiException(CatalogErrorCode.EVENT_NOT_PUBLISHED, "x"))
                    .when(unpublishEvent)
                    .handle(ORG, EVENT);

            mvc.perform(post(BASE + "/events/" + EVENT + "/unpublish").header("Authorization", manager))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("EVENT_NOT_PUBLISHED"));
        }

        @Test
        @DisplayName("huỷ lần hai: 409; xoá bản nháp: 204; xoá sự kiện đã bán: 409")
        void huy_va_xoa() throws Exception {
            doThrow(new ApiException(CatalogErrorCode.INVALID_EVENT_STATE, "x"))
                    .when(cancelEvent)
                    .cancel(ORG, EVENT);

            mvc.perform(post(BASE + "/events/" + EVENT + "/cancel").header("Authorization", manager))
                    .andExpect(status().isConflict());
            mvc.perform(delete(BASE + "/events/" + EVENT).header("Authorization", manager))
                    .andExpect(status().isNoContent());
            verify(cancelEvent).deleteDraft(ORG, EVENT);

            doThrow(new ApiException(CatalogErrorCode.INVALID_EVENT_STATE, "x"))
                    .when(cancelEvent)
                    .deleteDraft(ORG, EVENT);
            mvc.perform(delete(BASE + "/events/" + EVENT).header("Authorization", manager))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("đồng bộ lại tồn kho: trả số suất đã gửi")
        void resync() throws Exception {
            when(publishEvent.resyncInventory(ORG, EVENT)).thenReturn(3);

            mvc.perform(post(BASE + "/events/" + EVENT + "/resync-inventory").header("Authorization", manager))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessions").value(3));
        }
    }

    @Nested
    @DisplayName("Suất diễn")
    class SuatDien {

        private static final String PATH = BASE + "/events/" + EVENT + "/sessions";
        private static final String OPEN = "\"salesOpenAt\":\"2026-11-01T00:00:00Z\"";
        private static final String CLOSE = "\"salesCloseAt\":\"2026-11-30T00:00:00Z\"";
        private static final String START = "\"startsAt\":\"2026-12-01T12:00:00Z\"";

        @ParameterizedTest(name = "#{index}")
        @ValueSource(
                strings = {
                    "{}",
                    "{" + START + "," + OPEN + "}",
                    "{" + START + "," + CLOSE + "}",
                    "{\"startsAt\":\"mai\"," + OPEN + "," + CLOSE + "}",
                    "{\"startsAt\":\"2026-12-01 12:00\"," + OPEN + "," + CLOSE + "}",
                    "{" + START + "," + OPEN + "," + CLOSE + ",\"maxSeatedPerHold\":0}",
                    "{" + START + "," + OPEN + "," + CLOSE + ",\"maxTicketsPerCustomer\":-4}"
                })
        @DisplayName("thiếu mốc bắt buộc, ngày sai định dạng, trần mua vé ≤ 0: 400")
        void sai(String body) throws Exception {
            mvc.perform(json(post(PATH), body)).andExpect(status().isBadRequest());
            verifyNoInteractions(addSession);
        }

        @Test
        @DisplayName("giờ Việt Nam (+07:00) được đọc thành đúng thời điểm UTC")
        void mui_gio() throws Exception {
            mvc.perform(json(
                            post(PATH),
                            "{\"startsAt\":\"2026-12-24T19:30:00+07:00\",\"salesOpenAt\":\"2026-12-24T12:30:00+07:00\","
                                    + "\"salesCloseAt\":\"2026-12-24T19:00:00+07:00\"}"))
                    .andExpect(status().isCreated());

            verify(addSession)
                    .handle(
                            eq(ORG),
                            eq(EVENT),
                            eq(Instant.parse("2026-12-24T12:30:00Z")),
                            any(),
                            eq(Instant.parse("2026-12-24T05:30:00Z")),
                            eq(Instant.parse("2026-12-24T12:00:00Z")),
                            any(),
                            any(),
                            any(),
                            any());
        }

        @Test
        @DisplayName("suất của sự kiện đang bán: 409 từ handler được trả nguyên mã")
        void dang_ban() throws Exception {
            when(addSession.handle(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new ApiException(CatalogErrorCode.INVALID_EVENT_STATE, "x"));

            mvc.perform(json(post(PATH), "{" + START + "," + OPEN + "," + CLOSE + "}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_EVENT_STATE"));
        }

        @Test
        @DisplayName("sửa suất: trần âm 400; xoá suất không có: 404 SESSION_NOT_FOUND")
        void sua_va_xoa() throws Exception {
            mvc.perform(json(patch(PATH + "/" + SESSION), "{\"maxSeatedPerHold\":-1}"))
                    .andExpect(status().isBadRequest());
            doThrow(new ApiException(CatalogErrorCode.SESSION_NOT_FOUND, "x"))
                    .when(editSession)
                    .delete(ORG, EVENT, SESSION);

            mvc.perform(delete(PATH + "/" + SESSION).header("Authorization", manager))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
        }

        @Test
        @DisplayName("id suất không phải UUID: 400")
        void id_sai() throws Exception {
            mvc.perform(delete(PATH + "/xyz").header("Authorization", manager)).andExpect(status().isBadRequest());
            verifyNoInteractions(editSession);
        }
    }

    @Nested
    @DisplayName("Hạng vé")
    class HangVe {

        private static final String PATH = BASE + "/events/" + EVENT + "/sessions/" + SESSION + "/ticket-types";

        private String body(String price) {
            return "{\"venueZoneId\":\"" + ZONE + "\",\"name\":\"A\",\"priceVnd\":" + price + "}";
        }

        @Test
        @DisplayName("giá âm: 400 nêu trường priceVnd")
        void gia_am() throws Exception {
            mvc.perform(json(post(PATH), body("-1")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.meta.fields.priceVnd").exists());
            verifyNoInteractions(addTicketType);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"99999999999999999999", "1e30", "\"nghin\"", "1.5"})
        @DisplayName("giá tràn long / số mũ / chữ / số thực: 400, không bao giờ bị đọc thành số khác")
        void gia_bat_thuong(String price) throws Exception {
            mvc.perform(json(post(PATH), body(price))).andExpect(status().isBadRequest());
            verifyNoInteractions(addTicketType);
        }

        @Test
        @DisplayName("thiếu priceVnd: phải bị từ chối 400, không được âm thầm thành vé 0 đồng")
        void thieu_gia() throws Exception {
            // priceVnd là `long` nguyên thuỷ: thiếu trường thì Jackson điền 0 và @PositiveOrZero
            // cho qua — một request quên giá tạo ra vé miễn phí.
            mvc.perform(json(post(PATH), "{\"venueZoneId\":\"" + ZONE + "\",\"name\":\"A\"}"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(addTicketType);
        }

        @Test
        @DisplayName("thiếu khu hoặc tên trống: 400")
        void thieu_khu_hoac_ten() throws Exception {
            mvc.perform(json(post(PATH), "{\"name\":\"A\",\"priceVnd\":1}")).andExpect(status().isBadRequest());
            mvc.perform(json(post(PATH), "{\"venueZoneId\":\"" + ZONE + "\",\"name\":\" \",\"priceVnd\":1}"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(addTicketType);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(longs = {0L, 9_000_000_000L})
        @DisplayName("giá 0 và giá rất lớn nhưng vừa long: chuyển nguyên xuống handler")
        void gia_bien(long price) throws Exception {
            mvc.perform(json(post(PATH), body(Long.toString(price)))).andExpect(status().isCreated());

            verify(addTicketType).handle(eq(ORG), eq(EVENT), eq(SESSION), eq(ZONE), eq("A"), eq(price), anyInt());
        }

        @Test
        @DisplayName("khai giá lần hai cho cùng khu: 409 ZONE_ALREADY_PRICED")
        void trung_khu() throws Exception {
            doThrow(new ApiException(CatalogErrorCode.ZONE_ALREADY_PRICED, "x"))
                    .when(addTicketType)
                    .handle(any(), any(), any(), any(), anyString(), anyLong(), anyInt());

            mvc.perform(json(post(PATH), body("5")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ZONE_ALREADY_PRICED"));
        }

        @Test
        @DisplayName("sửa giá âm: 400; xoá hạng vé không có: 404; id sai: 400")
        void sua_xoa() throws Exception {
            mvc.perform(json(patch(PATH + "/" + ZONE), "{\"priceVnd\":-5}")).andExpect(status().isBadRequest());
            doThrow(new ApiException(CatalogErrorCode.TICKET_TYPE_NOT_FOUND, "x"))
                    .when(editTicketType)
                    .delete(ORG, EVENT, SESSION, ZONE);

            mvc.perform(delete(PATH + "/" + ZONE).header("Authorization", manager))
                    .andExpect(status().isNotFound());
            mvc.perform(delete(PATH + "/zzz").header("Authorization", manager)).andExpect(status().isBadRequest());
        }
    }
}
