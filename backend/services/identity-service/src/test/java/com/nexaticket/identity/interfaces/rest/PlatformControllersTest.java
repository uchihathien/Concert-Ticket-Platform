// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.identity.application.command.CreateOrganizationHandler;
import com.nexaticket.identity.application.command.GrantMembershipHandler;
import com.nexaticket.identity.application.query.OrganizationView;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.UserId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Khu vực {@code /v1/platform/**}: TenantFilter bỏ qua bước chọn tổ chức ở đây, nên toàn bộ việc
 * chặn nằm ở {@code @RequiresPermission} — chủ sở hữu tổ chức, người có mọi quyền ở nhà mình, phải
 * nhận 403 ở mọi endpoint.
 */
class PlatformControllersTest extends RestSliceTestBase {

    private static final String CREATE_BODY = "{\"name\":\"Nhà hát Lớn\",\"ownerEmail\":\"chu@example.com\"}";

    /** Mọi endpoint nền tảng có khai quyền, kèm thân hợp lệ nếu cần. */
    private static List<MockHttpServletRequestBuilder> platformEndpoints() {
        UUID org = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        return List.of(
                post("/v1/platform/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY),
                post("/v1/platform/organizations/{org}/members", org)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@example.com\",\"role\":\"ORG_ADMIN\"}"),
                post("/v1/platform/organizations/{org}/suspend", org),
                post("/v1/platform/organizations/{org}/activate", org),
                post("/v1/platform/users/{user}/password-reset", user),
                delete("/v1/platform/users/{user}/sessions", user),
                delete("/v1/platform/users/{user}/sessions/{sid}", user, "sid-1"),
                post("/v1/platform/users/{user}/disable", user),
                post("/v1/platform/users/{user}/enable", user),
                get("/v1/platform/audit-logs"));
    }

    @ParameterizedTest
    @EnumSource(
            value = Role.class,
            names = {"ORG_OWNER", "ORG_ADMIN", "EVENT_MANAGER", "CHECKIN_STAFF"})
    @DisplayName("mọi vai trò của tổ chức nhận 403 ở mọi endpoint nền tảng, handler không được gọi")
    void vai_tro_to_chuc_403(Role role) throws Exception {
        String token = as(role, ORG);
        for (MockHttpServletRequestBuilder request : platformEndpoints()) {
            mvc.perform(request.header(AUTH, token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        verifyNoInteractions(
                createOrganization,
                grantMembership,
                organizationLifecycle,
                sendPasswordReset,
                revokeSessions,
                userLifecycle,
                auditQueries);
    }

    @Test
    @DisplayName("không có token: 401 ở mọi endpoint nền tảng")
    void khong_token_401() throws Exception {
        for (MockHttpServletRequestBuilder request : platformEndpoints()) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/v1/platform/organizations")).andExpect(status().isUnauthorized());
    }

    @Nested
    @DisplayName("tổ chức")
    class ToChuc {

        @Test
        @DisplayName("tạo tổ chức: 201, Location trỏ tới tổ chức mới, token lời mời chủ sở hữu trả về một lần")
        void tao_to_chuc() throws Exception {
            when(createOrganization.handle(any()))
                    .thenReturn(new CreateOrganizationHandler.Result(
                            new OrganizationView(org(ORG), "nha-hat-lon", "Nhà hát Lớn", "ACTIVE", List.of()), "tok"));

            mvc.perform(post("/v1/platform/organizations")
                            .header(AUTH, asSuperAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CREATE_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/v1/organizations/" + org(ORG)))
                    .andExpect(jsonPath("$.invitationToken").value("tok"))
                    .andExpect(jsonPath("$.ownerEmail").value("chu@example.com"));
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "{\"ownerEmail\":\"chu@example.com\"}",
                    "{\"name\":\"\",\"ownerEmail\":\"chu@example.com\"}",
                    "{\"name\":\"A\",\"ownerEmail\":\"khong-phai-email\"}",
                    "{\"name\":\"A\"}"
                })
        @DisplayName("tên trống, thiếu hay sai email chủ sở hữu: 400 trước khi tới handler")
        void dau_vao_sai(String body) throws Exception {
            mvc.perform(post("/v1/platform/organizations")
                            .header(AUTH, asSuperAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
            verifyNoInteractions(createOrganization);
        }

        @Test
        @DisplayName("tên quá 200 ký tự hay slug quá 64 ký tự: 400")
        void qua_dai() throws Exception {
            String token = asSuperAdmin();
            mvc.perform(post("/v1/platform/organizations")
                            .header(AUTH, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"" + "a".repeat(201) + "\",\"ownerEmail\":\"c@example.com\"}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post("/v1/platform/organizations")
                            .header(AUTH, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"A\",\"slug\":\"" + "a".repeat(65)
                                    + "\",\"ownerEmail\":\"c@example.com\"}"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(createOrganization);
        }

        @Test
        @DisplayName("cấp thành viên: handler nhận tổ chức trên đường dẫn; kết quả INVITED trả kèm token")
        void cap_thanh_vien() throws Exception {
            when(grantMembership.handle(ORG, "a@example.com", Role.ORG_ADMIN))
                    .thenReturn(
                            new GrantMembershipHandler.Result("INVITED", "a@example.com", "ORG_ADMIN", null, "tok"));

            mvc.perform(post("/v1/platform/organizations/{org}/members", org(ORG))
                            .header(AUTH, asSuperAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@example.com\",\"role\":\"ORG_ADMIN\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("INVITED"))
                    .andExpect(jsonPath("$.invitationToken").value("tok"));
        }

        @Test
        @DisplayName("superadmin khoá tổ chức qua route nền tảng dù không là thành viên")
        void khoa_to_chuc() throws Exception {
            when(organizationQueries.byId(ORG))
                    .thenReturn(new OrganizationView(org(ORG), "s", "N", "SUSPENDED", List.of()));

            mvc.perform(post("/v1/platform/organizations/{org}/suspend", org(ORG))
                            .header(AUTH, asSuperAdmin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUSPENDED"));
            verify(organizationLifecycle).suspend(ORG);
        }

        @Test
        @DisplayName("id tổ chức trên route nền tảng không phải UUID là 400, không phải 500")
        void id_sai() throws Exception {
            mvc.perform(post("/v1/platform/organizations/khong-phai-uuid/suspend")
                            .header(AUTH, asSuperAdmin()))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(organizationLifecycle);
        }
    }

    @Nested
    @DisplayName("người dùng")
    class NguoiDung {

        @Test
        @DisplayName("vô hiệu hoá có lý do: 204 và lý do đi vào handler")
        void vo_hieu_hoa() throws Exception {
            UUID user = UUID.randomUUID();

            mvc.perform(post("/v1/platform/users/{user}/disable", user)
                            .header(AUTH, asSuperAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Nghỉ việc\"}"))
                    .andExpect(status().isNoContent());
            verify(userLifecycle).disable(UserId.of(user), "Nghỉ việc");
        }

        @Test
        @DisplayName("lý do vô hiệu hoá quá 500 ký tự bị từ chối như mọi lý do khác")
        void ly_do_vo_hieu_hoa_qua_dai() throws Exception {
            // DisableRequest khai @NotBlank @Size(max = 500) nhưng tham số không có @Valid, nên các
            // ràng buộc đó không bao giờ chạy: lý do vài megabyte đi thẳng vào nhật ký kiểm toán.
            mvc.perform(post("/v1/platform/users/{user}/disable", UUID.randomUUID())
                            .header(AUTH, asSuperAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"" + "x".repeat(501) + "\"}"))
                    .andExpect(status().isBadRequest());
            verify(userLifecycle, never()).disable(any(), anyString());
        }

        @Test
        @DisplayName("sid chứa ký tự ngoài [A-Za-z0-9._:-] hoặc quá 100 ký tự: 400")
        void sid_khong_hop_le() throws Exception {
            String token = asSuperAdmin();
            UUID user = UUID.randomUUID();

            mvc.perform(delete("/v1/platform/users/{user}/sessions/{sid}", user, "sid with space")
                            .header(AUTH, token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.meta.fields.sessionId").exists());
            mvc.perform(delete("/v1/platform/users/{user}/sessions/{sid}", user, "a".repeat(101))
                            .header(AUTH, token))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(revokeSessions);

            mvc.perform(delete("/v1/platform/users/{user}/sessions/{sid}", user, "f1e2-3c:4.d_5")
                            .header(AUTH, token))
                    .andExpect(status().isNoContent());
            verify(revokeSessions).revokeSession(UserId.of(user), "f1e2-3c:4.d_5", null, null);
        }

        @Test
        @DisplayName("thu hồi toàn bộ phiên ở đường nền tảng truyền tổ chức null")
        void thu_hoi_toan_bo() throws Exception {
            UUID user = UUID.randomUUID();

            mvc.perform(delete("/v1/platform/users/{user}/sessions", user).header(AUTH, asSuperAdmin()))
                    .andExpect(status().isNoContent());
            verify(revokeSessions).revokeAll(UserId.of(user), null, null);
        }

        @Test
        @DisplayName("nhật ký nền tảng: tham số lọc đi thẳng vào truy vấn")
        void nhat_ky() throws Exception {
            mvc.perform(get("/v1/platform/audit-logs")
                            .param("action", "USER_DISABLED")
                            .param("from", "2026-09-01T00:00:00Z")
                            .param("limit", "10")
                            .header(AUTH, asSuperAdmin()))
                    .andExpect(status().isOk());
            verify(auditQueries)
                    .forPlatform(
                            eq("USER_DISABLED"), eq(Instant.parse("2026-09-01T00:00:00Z")), isNull(), eq(10), eq(0));
        }

        @Test
        @DisplayName("id người dùng không phải UUID là 400, không phải 500")
        void id_sai() throws Exception {
            mvc.perform(post("/v1/platform/users/khong-phai-uuid/enable").header(AUTH, asSuperAdmin()))
                    .andExpect(status().isBadRequest());
            verify(userLifecycle, never()).enable(any());
            verify(auditQueries, never()).forPlatform(any(), any(), any(), anyInt(), anyInt());
        }
    }
}
