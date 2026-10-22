// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.identity.application.query.AccessQueries;
import com.nexaticket.identity.application.query.MyInvitationView;
import com.nexaticket.identity.application.query.OrganizationView;
import com.nexaticket.identity.application.query.ProfileView;
import com.nexaticket.kernel.access.Role;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * {@code /v1/me/**} và {@code /v1/roles}: những endpoint không có id nào trên đường dẫn — danh tính
 * lấy hoàn toàn từ token.
 */
class MeAndAccessControllerTest extends RestSliceTestBase {

    private static final ProfileView PROFILE =
            new ProfileView(UUID.randomUUID().toString(), "a@example.com", "A", null, false, List.of());

    @Nested
    @DisplayName("xác thực")
    class XacThuc {

        @Test
        @DisplayName("không có token: 401 kèm WWW-Authenticate Bearer, handler không được gọi")
        void khong_co_token() throws Exception {
            mvc.perform(get("/v1/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));
            verifyNoInteractions(organizationQueries);
        }

        @Test
        @DisplayName("token sai chữ ký: 401, không tra membership")
        void token_sai() throws Exception {
            mvc.perform(get("/v1/me").header(AUTH, "Bearer bad-token")).andExpect(status().isUnauthorized());
            verifyNoInteractions(membershipLookup, organizationQueries);
        }

        @Test
        @DisplayName("header Authorization sai lược đồ (Basic) bị coi như không có token")
        void sai_luoc_do() throws Exception {
            mvc.perform(get("/v1/me").header(AUTH, "Basic YWRtaW46YWRtaW4=")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("/v1/roles cũng đòi đăng nhập — ma trận mô tả bề mặt quản trị")
        void ma_tran_doi_dang_nhap() throws Exception {
            mvc.perform(get("/v1/roles")).andExpect(status().isUnauthorized());
            mvc.perform(get("/v1/me/permissions")).andExpect(status().isUnauthorized());
            mvc.perform(get("/v1/me/invitations")).andExpect(status().isUnauthorized());
            verifyNoInteractions(accessQueries);
        }
    }

    @Nested
    @DisplayName("PATCH /v1/me")
    class SuaHoSo {

        @Test
        @DisplayName("chỉ tên và số điện thoại đi vào handler; email, vai trò, superadmin, tổ chức bị bỏ qua")
        void chong_mass_assignment() throws Exception {
            when(organizationQueries.currentProfile()).thenReturn(PROFILE);

            mvc.perform(patch("/v1/me")
                            .header(AUTH, asCustomer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    """
                                    {"fullName":"Tên Mới","phone":"0912 345 678",
                                     "email":"ke-gian@example.com","superAdmin":true,"roles":["SUPER_ADMIN"],
                                     "id":"%s","organizations":[{"organizationId":"%s","role":"ORG_OWNER"}]}
                                    """
                                            .formatted(UUID.randomUUID(), org(ORG))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("a@example.com"))
                    .andExpect(jsonPath("$.superAdmin").value(false));

            verify(updateProfile).handle("Tên Mới", "0912 345 678");
        }

        @Test
        @DisplayName("số điện thoại có chữ cái: 400 VALIDATION_FAILED, lỗi chỉ ra đúng trường")
        void so_dien_thoai_sai() throws Exception {
            mvc.perform(patch("/v1/me")
                            .header(AUTH, asCustomer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"0912abc\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.type").value("https://nexaticket.vn/errors/validation-failed"))
                    .andExpect(jsonPath("$.meta.fields.phone").exists());
            verify(updateProfile, never()).handle(any(), any());
        }

        @Test
        @DisplayName("tên quá 120 ký tự hay số điện thoại quá 24 ký tự đều bị từ chối")
        void qua_dai() throws Exception {
            mvc.perform(patch("/v1/me")
                            .header(AUTH, asCustomer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fullName\":\"" + "a".repeat(121) + "\",\"phone\":\"" + "1".repeat(25) + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.meta.fields.fullName").exists())
                    .andExpect(jsonPath("$.meta.fields.phone").exists());
        }

        @Test
        @DisplayName("JSON hỏng: 400 'Malformed request body', không lộ chi tiết parser")
        void json_hong() throws Exception {
            mvc.perform(patch("/v1/me")
                            .header(AUTH, asCustomer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fullName\":"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.detail").value("Malformed request body"));
        }

        @Test
        @DisplayName("thân không phải JSON (text/plain) là lỗi của người gọi — 415, không phải 500")
        void sai_content_type() throws Exception {
            mvc.perform(patch("/v1/me")
                            .header(AUTH, asCustomer())
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("fullName=A"))
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("phương thức không hỗ trợ (PUT /v1/me) là 405, không phải 500")
        void sai_phuong_thuc() throws Exception {
            mvc.perform(put("/v1/me")
                            .header(AUTH, asCustomer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isMethodNotAllowed());
        }
    }

    @Nested
    @DisplayName("lời mời của tôi")
    class LoiMoiCuaToi {

        @Test
        @DisplayName("danh sách không chứa token hay hash")
        void danh_sach() throws Exception {
            when(organizationQueries.myPendingInvitations())
                    .thenReturn(List.of(new MyInvitationView(
                            UUID.randomUUID().toString(),
                            org(ORG),
                            "Nhà hát",
                            "EVENT_MANAGER",
                            "2026-09-15T00:00:00Z",
                            false)));

            mvc.perform(get("/v1/me/invitations").header(AUTH, asCustomer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].organizationName").value("Nhà hát"))
                    .andExpect(jsonPath("$[0].token").doesNotExist())
                    .andExpect(jsonPath("$[0].tokenHash").doesNotExist());
        }

        @Test
        @DisplayName("chấp nhận theo id trả tóm tắt tổ chức, không trả danh sách thành viên")
        void chap_nhan() throws Exception {
            UUID invitation = UUID.randomUUID();
            when(acceptInvitation.handle(invitation))
                    .thenReturn(new OrganizationView(org(ORG), "nha-hat", "Nhà hát", "ACTIVE", List.of()));

            mvc.perform(post("/v1/me/invitations/{id}/accept", invitation).header(AUTH, asCustomer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(org(ORG)))
                    .andExpect(jsonPath("$.memberCount").value(0))
                    .andExpect(jsonPath("$.members").doesNotExist());
        }

        @Test
        @DisplayName("id lời mời không phải UUID là 400, không phải 500")
        void id_khong_phai_uuid() throws Exception {
            mvc.perform(post("/v1/me/invitations/khong-phai-uuid/accept").header(AUTH, asCustomer()))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(acceptInvitation);
        }
    }

    @Nested
    @DisplayName("ma trận phân quyền")
    class MaTran {

        @Test
        @DisplayName("người đã đăng nhập đọc được ma trận và quyền của chính mình")
        void doc_ma_tran() throws Exception {
            when(accessQueries.allRoles())
                    .thenReturn(List.of(
                            new AccessQueries.RoleView("ORG_ADMIN", "ORGANIZATION", List.of("CATALOG_MANAGE"))));
            when(accessQueries.myPermissions())
                    .thenReturn(new AccessQueries.MyPermissions(
                            "u", false, List.of(), Map.of(org(ORG), List.of("CHECKIN_SCAN"))));

            String token = as(Role.CHECKIN_STAFF, ORG);
            mvc.perform(get("/v1/roles").header(AUTH, token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].role").value("ORG_ADMIN"));
            mvc.perform(get("/v1/me/permissions").header(AUTH, token))
                    .andExpect(status().isOk())
                    .andExpect(
                            jsonPath("$.organizations['" + org(ORG) + "'][0]").value("CHECKIN_SCAN"));
        }

        @Test
        @DisplayName("/v1/me/permissions không nhận tham số chỉ sang người khác")
        void khong_do_quyen_nguoi_khac() throws Exception {
            when(accessQueries.myPermissions())
                    .thenReturn(new AccessQueries.MyPermissions("me", false, List.of(), Map.of()));

            mvc.perform(get("/v1/me/permissions")
                            .param("userId", UUID.randomUUID().toString())
                            .header(AUTH, asCustomer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value("me"));
        }
    }

    @Test
    @DisplayName("đường dẫn không tồn tại trả 404 dạng RFC 7807, không phải 500")
    void duong_dan_khong_ton_tai() throws Exception {
        mvc.perform(get("/v1/khong-ton-tai").header(AUTH, asCustomer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(organizationQueries, never()).byId(any());
        verify(jwtDecoder).decode(anyString());
    }
}
