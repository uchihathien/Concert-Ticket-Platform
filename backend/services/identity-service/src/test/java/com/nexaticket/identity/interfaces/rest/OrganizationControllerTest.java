// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
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

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.application.query.OrganizationView;
import com.nexaticket.identity.application.query.PurchaseLimitsView;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.web.error.ApiException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

/**
 * Khu vực tổ chức qua chuỗi bảo mật thật: TenantFilter (404 với người ngoài, kể cả đường dẫn mã hoá
 * lạ), PermissionGuard (403 khi thiếu quyền), bean validation (400), và hình dạng lỗi RFC 7807.
 */
class OrganizationControllerTest extends RestSliceTestBase {

    private static final String MEMBERS = "/v1/organizations/{org}/members";

    private static OrganizationView view() {
        return new OrganizationView(org(ORG), "to-chuc", "Tổ chức", "ACTIVE", List.of());
    }

    @Nested
    @DisplayName("ranh giới tổ chức")
    class RanhGioi {

        @Test
        @DisplayName("thành viên tổ chức khác nhận 404, không phải 403 — 403 xác nhận tổ chức tồn tại")
        void to_chuc_khac_404() throws Exception {
            mvc.perform(get(MEMBERS, org(ORG)).header(AUTH, as(Role.ORG_OWNER, OTHER_ORG)))
                    .andExpect(status().isNotFound());
            verifyNoInteractions(organizationQueries);
        }

        @Test
        @DisplayName(
                "superadmin vào route /v1/organizations của tổ chức mình không thuộc: 404 — đường của họ là /v1/platform")
        void superadmin_khong_di_route_to_chuc() throws Exception {
            mvc.perform(get("/v1/organizations/{org}", org(ORG)).header(AUTH, asSuperAdmin()))
                    .andExpect(status().isNotFound());
            verifyNoInteractions(organizationQueries);
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "/v1/organizations/%3111111111-1111-4111-a111-111111111111/members",
                    "/v1/organizations/11111111-1111-4111-A111-111111111111/members",
                    "/v1/organizations/------------------------------------/members",
                    "/v1/organizations/not-a-uuid/members"
                })
        @DisplayName(
                "đoạn tổ chức không phải UUID chuẩn bị chặn ở filter với 404 — kể cả khi người gọi chỉ thuộc một tổ chức")
        void duong_dan_la(String path) throws Exception {
            // Người gọi chỉ thuộc đúng ORG: nhánh dự phòng "một tổ chức thì lấy tổ chức đó" chính là
            // nhánh từng biến một UUID mã hoá phần trăm thành một lần vượt rào (TenantPath).
            mvc.perform(get(URI.create(path)).header(AUTH, as(Role.ORG_OWNER, OTHER_ORG)))
                    .andExpect(status().isNotFound());
            verifyNoInteractions(organizationQueries);
        }

        @Test
        @DisplayName("JWT hợp lệ nhưng identity từ chối (khoá, phiên thu hồi): 401 UNAUTHENTICATED dạng RFC 7807")
        void danh_tinh_bi_tu_choi() throws Exception {
            mvc.perform(patch(MEMBERS + "/{user}", org(ORG), UUID.randomUUID())
                            .header(AUTH, asRejectedIdentity())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"EVENT_MANAGER\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                    .andExpect(jsonPath("$.status").value(401));
            verifyNoInteractions(manageMembers);
        }

        @Test
        @DisplayName("thành viên bình thường đọc được bảng thành viên của tổ chức mình")
        void thanh_vien_doc_duoc() throws Exception {
            when(organizationQueries.members(ORG)).thenReturn(List.of());

            mvc.perform(get(MEMBERS, org(ORG)).header(AUTH, as(Role.CHECKIN_STAFF, ORG)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("quản lý thành viên")
    class ThanhVien {

        @ParameterizedTest
        @EnumSource(
                value = Role.class,
                names = {"EVENT_MANAGER", "CHECKIN_STAFF"})
        @DisplayName("vai trò dưới ORG_ADMIN: 403 FORBIDDEN dạng RFC 7807 ở mọi lệnh quản lý người")
        void vai_tro_thap_403(Role role) throws Exception {
            String token = as(role, ORG);
            UUID user = UUID.randomUUID();

            mvc.perform(patch(MEMBERS + "/{user}", org(ORG), user)
                            .header(AUTH, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"ORG_ADMIN\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.type").value("https://nexaticket.vn/errors/forbidden"));
            mvc.perform(delete(MEMBERS + "/{user}", org(ORG), user).header(AUTH, token))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/v1/organizations/{org}/invitations", org(ORG))
                            .header(AUTH, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@example.com\",\"role\":\"EVENT_MANAGER\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/v1/organizations/{org}/invitations", org(ORG)).header(AUTH, token))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/v1/organizations/{org}/invitations/{id}", org(ORG), UUID.randomUUID())
                            .header(AUTH, token))
                    .andExpect(status().isForbidden());
            mvc.perform(post(MEMBERS + "/{user}/password-reset", org(ORG), user).header(AUTH, token))
                    .andExpect(status().isForbidden());
            mvc.perform(delete(MEMBERS + "/{user}/sessions", org(ORG), user).header(AUTH, token))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/v1/organizations/{org}/audit-logs", org(ORG)).header(AUTH, token))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(manageMembers, inviteMember, revokeInvitation, sendPasswordReset, revokeSessions);
        }

        @Test
        @DisplayName("ORG_ADMIN đổi vai trò: handler nhận đúng tổ chức trên đường dẫn và người dùng")
        void doi_vai_tro() throws Exception {
            UUID user = UUID.randomUUID();
            when(organizationQueries.members(ORG)).thenReturn(List.of());

            mvc.perform(patch(MEMBERS + "/{user}", org(ORG), user)
                            .header(AUTH, as(Role.ORG_ADMIN, ORG))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"EVENT_MANAGER\",\"organizationId\":\"" + org(OTHER_ORG) + "\"}"))
                    .andExpect(status().isOk());

            verify(manageMembers).changeRole(ORG, UserId.of(user), Role.EVENT_MANAGER);
        }

        @Test
        @DisplayName("thiếu vai trò: 400 với lỗi ở trường role; vai trò lạ: 400 malformed")
        void vai_tro_khong_hop_le() throws Exception {
            String token = as(Role.ORG_OWNER, ORG);

            mvc.perform(patch(MEMBERS + "/{user}", org(ORG), UUID.randomUUID())
                            .header(AUTH, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.meta.fields.role").exists());
            mvc.perform(patch(MEMBERS + "/{user}", org(ORG), UUID.randomUUID())
                            .header(AUTH, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"GOD_MODE\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            verifyNoInteractions(manageMembers);
        }

        @Test
        @DisplayName("lỗi nghiệp vụ LAST_OWNER đi ra đúng 409 kèm mã ổn định")
        void last_owner_409() throws Exception {
            UUID user = UUID.randomUUID();
            doThrow(new ApiException(IdentityErrorCode.LAST_OWNER, "Không thể xoá chủ sở hữu cuối cùng"))
                    .when(manageMembers)
                    .remove(ORG, UserId.of(user));

            mvc.perform(delete(MEMBERS + "/{user}", org(ORG), user).header(AUTH, as(Role.ORG_OWNER, ORG)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("LAST_OWNER"))
                    .andExpect(jsonPath("$.type").value("https://nexaticket.vn/errors/last-owner"))
                    .andExpect(jsonPath("$.title").value("last owner"))
                    .andExpect(jsonPath("$.detail").exists());
        }

        @Test
        @DisplayName("id người dùng không phải UUID là 400, không phải 500")
        void user_id_sai() throws Exception {
            mvc.perform(delete(MEMBERS + "/khong-phai-uuid", org(ORG)).header(AUTH, as(Role.ORG_OWNER, ORG)))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(manageMembers);
        }
    }

    @Nested
    @DisplayName("lời mời")
    class LoiMoi {

        @Test
        @DisplayName("tạo lời mời trả token đúng một lần; tổ chức lấy từ đường dẫn, không từ thân")
        void tao_loi_moi() throws Exception {
            when(inviteMember.handle(eq(ORG), eq("a@example.com"), eq(Role.EVENT_MANAGER)))
                    .thenReturn("token-tho");

            mvc.perform(post("/v1/organizations/{org}/invitations", org(ORG))
                            .header(AUTH, as(Role.ORG_ADMIN, ORG))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@example.com\",\"role\":\"EVENT_MANAGER\",\"organizationId\":\""
                                    + org(OTHER_ORG) + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").value("token-tho"))
                    .andExpect(jsonPath("$.role").value("EVENT_MANAGER"));
            verify(inviteMember).handle(ORG, "a@example.com", Role.EVENT_MANAGER);
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "{\"email\":\"khong-phai-email\",\"role\":\"EVENT_MANAGER\"}",
                    "{\"email\":\"\",\"role\":\"EVENT_MANAGER\"}",
                    "{\"email\":\"a@example.com\"}"
                })
        @DisplayName("email sai hay thiếu vai trò: 400 trước khi tới handler")
        void dau_vao_sai(String body) throws Exception {
            mvc.perform(post("/v1/organizations/{org}/invitations", org(ORG))
                            .header(AUTH, as(Role.ORG_ADMIN, ORG))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            verifyNoInteractions(inviteMember);
        }

        @Test
        @DisplayName("thu hồi lời mời trả 204")
        void thu_hoi() throws Exception {
            UUID invitation = UUID.randomUUID();

            mvc.perform(delete("/v1/organizations/{org}/invitations/{id}", org(ORG), invitation)
                            .header(AUTH, as(Role.ORG_ADMIN, ORG)))
                    .andExpect(status().isNoContent());
            verify(revokeInvitation).handle(ORG, invitation);
        }

        @Test
        @DisplayName("nhận lời mời bằng token cần đăng nhập")
        void nhan_loi_moi() throws Exception {
            mvc.perform(post("/v1/invitations/{token}/accept", "abc")).andExpect(status().isUnauthorized());

            when(acceptInvitation.handle("abc")).thenReturn(view());
            mvc.perform(post("/v1/invitations/{token}/accept", "abc").header(AUTH, asCustomer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.slug").value("to-chuc"));
        }
    }

    @Nested
    @DisplayName("trần mua vé")
    class TranMuaVe {

        private static final String LIMITS = "/v1/organizations/{org}/purchase-limits";

        @ParameterizedTest
        @ValueSource(strings = {"0", "-1", "-2147483648"})
        @DisplayName("trần 0 hay âm bị từ chối — 'mua được 0 vé' không phải một trần")
        void tran_khong_duong(String value) throws Exception {
            mvc.perform(put(LIMITS, org(ORG))
                            .header(AUTH, as(Role.ORG_ADMIN, ORG))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"maxSeatedPerHold\":" + value + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.meta.fields.maxSeatedPerHold").exists());
            verifyNoInteractions(purchaseLimits);
        }

        @Test
        @DisplayName("bỏ trống mọi trường là hợp lệ: kế thừa trần nền tảng, null được trả về tường minh")
        void bo_trong() throws Exception {
            when(purchaseLimits.set(ORG, null, null, null, null))
                    .thenReturn(new PurchaseLimitsView(null, null, null, null));

            mvc.perform(put(LIMITS, org(ORG))
                            .header(AUTH, as(Role.ORG_OWNER, ORG))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.maxSeatedPerHold").value(nullValue()))
                    .andExpect(jsonPath("$.maxTicketsPerCustomer").hasJsonPath());
        }

        @Test
        @DisplayName("giá trị 1 (biên dưới) được nhận; số thập phân và chuỗi thì không")
        void bien() throws Exception {
            String token = as(Role.ORG_ADMIN, ORG);
            when(purchaseLimits.set(any(TenantId.class), any(), any(), any(), any()))
                    .thenReturn(new PurchaseLimitsView(1, 1, 1, 1));

            mvc.perform(
                            put(LIMITS, org(ORG))
                                    .header(AUTH, token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"maxSeatedPerHold\":1,\"maxStandingPerHold\":1,\"maxUnitsPerHold\":1,\"maxTicketsPerCustomer\":1}"))
                    .andExpect(status().isOk());
            mvc.perform(put(LIMITS, org(ORG))
                            .header(AUTH, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"maxSeatedPerHold\":\"nhieu\"}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(put(LIMITS, org(ORG))
                            .header(AUTH, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"maxSeatedPerHold\":99999999999}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("EVENT_MANAGER không ghi được trần")
        void quan_ly_su_kien() throws Exception {
            mvc.perform(put(LIMITS, org(ORG))
                            .header(AUTH, as(Role.EVENT_MANAGER, ORG))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
            verify(purchaseLimits, never()).set(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("đổi tên, nhật ký, thu hồi phiên")
    class Khac {

        @Test
        @DisplayName("đổi tên: tên rỗng hay quá 200 ký tự là 400")
        void doi_ten() throws Exception {
            String token = as(Role.ORG_ADMIN, ORG);
            mvc.perform(patch("/v1/organizations/{org}", org(ORG))
                            .header(AUTH, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"  \"}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(patch("/v1/organizations/{org}", org(ORG))
                            .header(AUTH, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + "a".repeat(201) + "\"}"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(organizationLifecycle);

            when(organizationQueries.byId(ORG)).thenReturn(view());
            mvc.perform(patch("/v1/organizations/{org}", org(ORG))
                            .header(AUTH, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Tên mới\",\"status\":\"ACTIVE\",\"slug\":\"khac\"}"))
                    .andExpect(status().isOk());
            verify(organizationLifecycle).rename(ORG, "Tên mới");
        }

        @Test
        @DisplayName("lý do thu hồi phiên quá 500 ký tự là 400 với lỗi theo trường")
        void ly_do_qua_dai() throws Exception {
            mvc.perform(delete(MEMBERS + "/{user}/sessions", org(ORG), UUID.randomUUID())
                            .param("reason", "x".repeat(501))
                            .header(AUTH, as(Role.ORG_ADMIN, ORG)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.meta.fields.reason").exists());
            verifyNoInteractions(revokeSessions);
        }

        @Test
        @DisplayName("thu hồi phiên thành viên: 204, đúng tổ chức và người")
        void thu_hoi_phien() throws Exception {
            UUID user = UUID.randomUUID();

            mvc.perform(delete(MEMBERS + "/{user}/sessions", org(ORG), user)
                            .param("reason", "Mất máy")
                            .header(AUTH, as(Role.ORG_ADMIN, ORG)))
                    .andExpect(status().isNoContent());
            verify(revokeSessions).revokeAll(UserId.of(user), ORG, "Mất máy");
        }

        @Test
        @DisplayName("gửi hộ thư đặt lại mật khẩu: 202 — đã nhận việc, không hứa thư đã tới")
        void dat_lai_mat_khau() throws Exception {
            UUID user = UUID.randomUUID();

            mvc.perform(post(MEMBERS + "/{user}/password-reset", org(ORG), user).header(AUTH, as(Role.ORG_OWNER, ORG)))
                    .andExpect(status().isAccepted());
            verify(sendPasswordReset).handle(UserId.of(user), ORG);
        }

        @Test
        @DisplayName("nhật ký: mặc định trang 50, offset 0; tổ chức lấy từ đường dẫn")
        void nhat_ky_mac_dinh() throws Exception {
            mvc.perform(get("/v1/organizations/{org}/audit-logs", org(ORG)).header(AUTH, as(Role.ORG_ADMIN, ORG)))
                    .andExpect(status().isOk());
            verify(auditQueries).forOrganization(eq(ORG), isNull(), isNull(), isNull(), eq(50), eq(0));
        }

        @ParameterizedTest
        @ValueSource(strings = {"limit=abc", "from=hom-qua", "offset=1.5"})
        @DisplayName("tham số nhật ký sai kiểu là 400 của người gọi, không phải 500")
        void nhat_ky_tham_so_sai(String query) throws Exception {
            String[] pair = query.split("=");
            mvc.perform(get("/v1/organizations/{org}/audit-logs", org(ORG))
                            .param(pair[0], pair[1])
                            .header(AUTH, as(Role.ORG_ADMIN, ORG)))
                    .andExpect(status().isBadRequest());
            verify(auditQueries, never()).forOrganization(any(), any(), any(), any(), anyInt(), anyInt());
        }
    }
}
