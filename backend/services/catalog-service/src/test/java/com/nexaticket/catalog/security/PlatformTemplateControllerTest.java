// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.PlatformAccess;
import com.nexaticket.catalog.application.command.ManageTemplateHandler;
import com.nexaticket.catalog.application.command.ReplaceTemplateZonesHandler;
import com.nexaticket.catalog.application.query.TemplateQueries;
import com.nexaticket.catalog.domain.model.AdmissionKind;
import com.nexaticket.catalog.domain.model.ConcertTemplate;
import com.nexaticket.catalog.domain.model.TemplateStatus;
import com.nexaticket.catalog.domain.model.TemplateZone;
import com.nexaticket.catalog.domain.port.ConcertTemplateRepository;
import com.nexaticket.catalog.interfaces.rest.PlatformTemplateController;
import com.nexaticket.catalog.support.WebSliceBase;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Khung concert của nền tảng. Cửa quyền {@link PlatformAccess}, các handler và truy vấn là bản
 * THẬT (chỉ kho là mock), vì đây là khu vực chỉ Tổng công ty được vào — kiểm bằng handler giả sẽ
 * chứng minh không gì cả.
 */
@WebMvcTest(controllers = PlatformTemplateController.class)
@Import({
    PlatformAccess.class,
    CatalogAccess.class,
    ManageTemplateHandler.class,
    ReplaceTemplateZonesHandler.class,
    TemplateQueries.class
})
class PlatformTemplateControllerTest extends WebSliceBase {

    private static final UUID ORG = UUID.fromString("11111111-1111-4111-a111-111111111111");
    private static final String BASE = "/v1/platform/concert-templates";

    @MockitoBean
    ConcertTemplateRepository templates;

    private String superAdmin;
    private ConcertTemplate draft;

    @BeforeEach
    void dangNhap() {
        superAdmin = bearer(tokenFor(UUID.randomUUID(), Map.of(), true));
        draft = new ConcertTemplate(
                UUID.randomUUID(), "ARENA", "Arena", "concert", null, TemplateStatus.DRAFT, List.of());
        when(templates.findById(draft.id())).thenReturn(Optional.of(draft));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body, String token) {
        return req.header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static final String ZONE =
            "{\"zoneCode\":\"VIP\",\"name\":\"VIP\",\"kind\":\"SEATED\",\"rowCount\":2,\"seatsPerRow\":3}";

    @Nested
    @DisplayName("Quyền truy cập")
    class QuyenTruyCap {

        @Test
        @DisplayName("không token: 401 trên mọi đường")
        void khong_token() throws Exception {
            mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
            mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(delete(BASE + "/" + draft.id())).andExpect(status().isUnauthorized());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(
                value = Role.class,
                names = {"ORG_OWNER", "ORG_ADMIN", "EVENT_MANAGER", "CHECKIN_STAFF"})
        @DisplayName("mọi vai trò của tổ chức (kể cả chủ sở hữu): 403 ở đọc, tạo, kích hoạt, lưu trữ, xoá")
        void vai_tro_to_chuc(Role role) throws Exception {
            String token = bearer(tokenFor(UUID.randomUUID(), Map.of(TenantId.of(ORG), role), false));
            String id = draft.id().toString();

            mvc.perform(get(BASE).header("Authorization", token)).andExpect(status().isForbidden());
            mvc.perform(get(BASE + "/" + id).header("Authorization", token)).andExpect(status().isForbidden());
            mvc.perform(json(post(BASE), "{\"code\":\"X\",\"name\":\"X\",\"category\":\"c\"}", token))
                    .andExpect(status().isForbidden());
            mvc.perform(post(BASE + "/" + id + "/activate").header("Authorization", token))
                    .andExpect(status().isForbidden());
            mvc.perform(post(BASE + "/" + id + "/archive").header("Authorization", token))
                    .andExpect(status().isForbidden());
            mvc.perform(delete(BASE + "/" + id).header("Authorization", token)).andExpect(status().isForbidden());
            verify(templates, never()).insert(any());
            verify(templates, never()).delete(any());
        }

        @Test
        @DisplayName("khách không thuộc tổ chức nào: 403, và 403 không xác nhận khung có tồn tại hay không")
        void khach() throws Exception {
            String token = bearer(customerToken(UUID.randomUUID()));

            mvc.perform(get(BASE + "/" + draft.id()).header("Authorization", token))
                    .andExpect(status().isForbidden());
            mvc.perform(get(BASE + "/" + UUID.randomUUID()).header("Authorization", token))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(templates);
        }

        @Test
        @DisplayName("superadmin qua được TenantFilter dù không thuộc tổ chức nào: 200")
        void superadmin() throws Exception {
            when(templates.findAll(null)).thenReturn(List.of(draft));

            mvc.perform(get(BASE).header("Authorization", superAdmin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].code").value("ARENA"));
        }
    }

    @Nested
    @DisplayName("Vòng đời khung")
    class VongDoi {

        @Test
        @DisplayName("tạo: 201, mã được chuẩn hoá in hoa")
        void tao() throws Exception {
            when(templates.codeExists("NEW_1")).thenReturn(false);
            org.mockito.Mockito.doAnswer(inv -> {
                        ConcertTemplate t = inv.getArgument(0);
                        when(templates.findById(t.id())).thenReturn(Optional.of(t));
                        return null;
                    })
                    .when(templates)
                    .insert(any());

            mvc.perform(json(post(BASE), "{\"code\":\"new_1\",\"name\":\"Mới\",\"category\":\"concert\"}", superAdmin))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value("NEW_1"))
                    .andExpect(jsonPath("$.status").value("DRAFT"));
        }

        @ParameterizedTest(name = "#{index}")
        @ValueSource(
                strings = {
                    "{}",
                    "{\"code\":\"\",\"name\":\"A\",\"category\":\"c\"}",
                    "{\"code\":\"bad code!\",\"name\":\"A\",\"category\":\"c\"}",
                    "{\"code\":\"../etc\",\"name\":\"A\",\"category\":\"c\"}",
                    "{\"code\":\"A\",\"name\":\" \",\"category\":\"c\"}",
                    "{\"code\":\"A\",\"name\":\"A\",\"category\":\"\"}",
                    "{\"code\":\"A\",\"name\":\"A\",\"category\":\"c\",\"description\":\"%s\"}"
                })
        @DisplayName("thiếu trường, mã có ký tự lạ, tên trống, mô tả > 2000 ký tự: 400")
        void tao_sai(String body) throws Exception {
            mvc.perform(json(post(BASE), body.replace("%s", "a".repeat(2001)), superAdmin))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            verify(templates, never()).insert(any());
        }

        @Test
        @DisplayName("mã dài 41 ký tự: 400")
        void ma_qua_dai() throws Exception {
            mvc.perform(json(
                            post(BASE),
                            "{\"code\":\"" + "A".repeat(41) + "\",\"name\":\"A\",\"category\":\"c\"}",
                            superAdmin))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("mã trùng: 409 TEMPLATE_CODE_TAKEN")
        void ma_trung() throws Exception {
            when(templates.codeExists("ARENA")).thenReturn(true);

            mvc.perform(json(post(BASE), "{\"code\":\"arena\",\"name\":\"A\",\"category\":\"c\"}", superAdmin))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TEMPLATE_CODE_TAKEN"));
        }

        @Test
        @DisplayName("kích hoạt khung rỗng: 409 TEMPLATE_WITHOUT_ZONE")
        void kich_hoat_rong() throws Exception {
            mvc.perform(post(BASE + "/" + draft.id() + "/activate").header("Authorization", superAdmin))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TEMPLATE_WITHOUT_ZONE"));
        }

        @Test
        @DisplayName("nháp → mở → lưu trữ → về nháp qua HTTP")
        void chuyen_trang_thai() throws Exception {
            draft.replaceZones(List.of(
                    TemplateZone.create(UUID.randomUUID(), "VIP", "VIP", AdmissionKind.SEATED, 2, 2, null, 0, 1L)));

            mvc.perform(post(BASE + "/" + draft.id() + "/activate").header("Authorization", superAdmin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
            mvc.perform(post(BASE + "/" + draft.id() + "/archive").header("Authorization", superAdmin))
                    .andExpect(jsonPath("$.status").value("ARCHIVED"));
            mvc.perform(post(BASE + "/" + draft.id() + "/draft").header("Authorization", superAdmin))
                    .andExpect(jsonPath("$.status").value("DRAFT"));
        }

        @Test
        @DisplayName("khung không có: 404 TEMPLATE_NOT_FOUND; id không phải UUID: 400")
        void khong_co() throws Exception {
            mvc.perform(get(BASE + "/" + UUID.randomUUID()).header("Authorization", superAdmin))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("TEMPLATE_NOT_FOUND"));
            mvc.perform(get(BASE + "/abc").header("Authorization", superAdmin)).andExpect(status().isBadRequest());
            mvc.perform(delete(BASE + "/abc").header("Authorization", superAdmin))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("xoá: 204 khi chưa ai dùng, 409 TEMPLATE_IN_USE khi đã có địa điểm dựng từ khung")
        void xoa() throws Exception {
            mvc.perform(delete(BASE + "/" + draft.id()).header("Authorization", superAdmin))
                    .andExpect(status().isNoContent());
            verify(templates).delete(draft.id());

            when(templates.hasVenuesFromTemplate(draft.id())).thenReturn(true);
            mvc.perform(delete(BASE + "/" + draft.id()).header("Authorization", superAdmin))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TEMPLATE_IN_USE"));
        }

        @Test
        @DisplayName("danh sách lọc theo trạng thái: giá trị lạ bị từ chối, không trả hết")
        void loc_trang_thai() throws Exception {
            mvc.perform(get(BASE).param("status", "ACTIVEX").header("Authorization", superAdmin))
                    .andExpect(status().is4xxClientError());
            verify(templates, never()).findAll(any());
        }

        @Test
        @DisplayName("sửa: mô tả 2001 ký tự → 400; tên mới được áp")
        void sua() throws Exception {
            mvc.perform(json(
                            patch(BASE + "/" + draft.id()),
                            "{\"description\":\"" + "a".repeat(2001) + "\"}",
                            superAdmin))
                    .andExpect(status().isBadRequest());
            mvc.perform(json(patch(BASE + "/" + draft.id()), "{\"name\":\"Tên mới\"}", superAdmin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Tên mới"));
        }
    }

    @Nested
    @DisplayName("Khai tập khu")
    class KhaiKhu {

        @Test
        @DisplayName("thay tập khu hợp lệ: 200 với sức chứa mới")
        void hop_le() throws Exception {
            mvc.perform(json(put(BASE + "/" + draft.id() + "/zones"), "{\"zones\":[" + ZONE + "]}", superAdmin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.capacity").value(6))
                    .andExpect(jsonPath("$.zones[0].zoneCode").value("VIP"));
        }

        @ParameterizedTest(name = "#{index}")
        @ValueSource(
                strings = {
                    "{}",
                    "{\"zones\":[]}",
                    "{\"zones\":[{\"zoneCode\":\"A\",\"name\":\"A\",\"kind\":\"SEATED\",\"rowCount\":2,\"seatsPerRow\":2,\"suggestedPriceVnd\":-1}]}",
                    "{\"zones\":[{\"zoneCode\":\"A\",\"name\":\"A\",\"kind\":\"VIP\",\"capacity\":2}]}",
                    "{\"zones\":[{\"zoneCode\":\"A\",\"name\":\"A\",\"kind\":\"STANDING\",\"capacity\":-5}]}",
                    "{\"stage\":{\"shape\":\"RECTANGLE\",\"x\":0,\"y\":0,\"width\":-3,\"height\":2},\"zones\":[" + ZONE
                            + "]}"
                })
        @DisplayName("tập khu rỗng, giá gợi ý âm, loại lạ, sức chứa âm, sân khấu âm: 400")
        void sai(String body) throws Exception {
            mvc.perform(json(put(BASE + "/" + draft.id() + "/zones"), body, superAdmin))
                    .andExpect(status().isBadRequest());
            verify(templates, never()).replaceZones(any());
        }

        @Test
        @DisplayName("mã khu trùng trong khung: 4xx có mã, không phải 500")
        void ma_khu_trung() throws Exception {
            mvc.perform(json(
                            put(BASE + "/" + draft.id() + "/zones"),
                            "{\"zones\":[" + ZONE + "," + ZONE + "]}",
                            superAdmin))
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("khu đứng thiếu sức chứa (qua được annotation): 4xx có mã, không phải 500")
        void khu_dung_thieu_suc_chua() throws Exception {
            mvc.perform(json(
                            put(BASE + "/" + draft.id() + "/zones"),
                            "{\"zones\":[{\"zoneCode\":\"A\",\"name\":\"A\",\"kind\":\"STANDING\"}]}",
                            superAdmin))
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("cung quét ngược: 422 ZONE_LAYOUT_INVALID")
        void cung_nguoc() throws Exception {
            String arc = "{\"zoneCode\":\"A\",\"name\":\"A\",\"kind\":\"SEATED\",\"rowCount\":2,\"seatsPerRow\":2,"
                    + "\"layoutShape\":\"ARC\",\"originX\":0,\"originY\":0,\"innerRadius\":5,\"startAngleDeg\":90,\"endAngleDeg\":10}";

            mvc.perform(json(put(BASE + "/" + draft.id() + "/zones"), "{\"zones\":[" + arc + "]}", superAdmin))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("ZONE_LAYOUT_INVALID"));
        }
    }
}
