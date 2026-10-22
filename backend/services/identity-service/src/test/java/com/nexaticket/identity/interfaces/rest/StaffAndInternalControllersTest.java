// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.application.command.RecordExternalAuditHandler;
import com.nexaticket.identity.application.command.StaffInvitationHandler;
import com.nexaticket.identity.application.query.StaffQueries;
import com.nexaticket.identity.application.query.UserContactView;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.MembershipLookup;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;

/** Mục Nhân sự và hai Open Host Service ({@code /internal/**}). */
class StaffAndInternalControllersTest extends RestSliceTestBase {

    private static final String STAFF = "/v1/organizations/{org}/staff";

    @Nested
    @DisplayName("mục Nhân sự")
    class NhanSu {

        @Test
        @DisplayName("mời nhân viên: 200 với kết quả và id; tổ chức lấy từ đường dẫn")
        void moi() throws Exception {
            UUID event = UUID.randomUUID();
            UUID created = UUID.randomUUID();
            when(staffInvitations.invite(ORG, "Nhân Viên", "nv@example.com", List.of(event)))
                    .thenReturn(new StaffInvitationHandler.Result("INVITED", created));

            mvc.perform(post(STAFF, org(ORG))
                            .header(AUTH, as(Role.ORG_ADMIN, ORG))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fullName\":\"Nhân Viên\",\"email\":\"nv@example.com\",\"eventIds\":[\"" + event
                                    + "\"],\"role\":\"ORG_OWNER\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("INVITED"))
                    .andExpect(jsonPath("$.userId").value(created.toString()));
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "{\"email\":\"nv@example.com\",\"eventIds\":[]}",
                    "{\"fullName\":\"A\",\"email\":\"sai\",\"eventIds\":[]}",
                    "{\"fullName\":\"A\",\"email\":\"nv@example.com\"}",
                    "{\"fullName\":\"A\",\"email\":\"nv@example.com\",\"eventIds\":[\"khong-phai-uuid\"]}"
                })
        @DisplayName("thiếu tên, email sai, thiếu danh sách sự kiện hay id sự kiện hỏng: 400")
        void dau_vao_sai(String body) throws Exception {
            mvc.perform(post(STAFF, org(ORG))
                            .header(AUTH, as(Role.ORG_OWNER, ORG))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(staffInvitations);
        }

        @Test
        @DisplayName("quá 200 sự kiện trong một lần gán: 400")
        void qua_nhieu_su_kien() throws Exception {
            String ids = IntStream.range(0, 201)
                    .mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                    .collect(Collectors.joining(","));

            mvc.perform(put(STAFF + "/{user}/events", org(ORG), UUID.randomUUID())
                            .header(AUTH, as(Role.ORG_ADMIN, ORG))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"eventIds\":[" + ids + "]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.meta.fields.eventIds").exists());
            verifyNoInteractions(staffInvitations);
        }

        @Test
        @DisplayName("nhân viên soát vé không xem được bảng Nhân sự, không mời, không gửi lại, không huỷ")
        void staff_khong_quan_ly() throws Exception {
            String token = as(Role.CHECKIN_STAFF, ORG);
            UUID user = UUID.randomUUID();

            mvc.perform(get(STAFF, org(ORG)).header(AUTH, token)).andExpect(status().isForbidden());
            mvc.perform(post(STAFF + "/{user}/invitation/resend", org(ORG), user)
                            .header(AUTH, token))
                    .andExpect(status().isForbidden());
            mvc.perform(delete(STAFF + "/{user}/invitation", org(ORG), user).header(AUTH, token))
                    .andExpect(status().isForbidden());
            mvc.perform(put(STAFF + "/{user}/events", org(ORG), user)
                            .header(AUTH, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"eventIds\":[]}"))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(staffInvitations, staffQueries);
        }

        @Test
        @DisplayName("nhân viên soát vé đọc được sự kiện được gán của chính mình")
        void su_kien_cua_toi() throws Exception {
            UUID event = UUID.randomUUID();
            when(staffQueries.myEvents(ORG)).thenReturn(new StaffQueries.MyEvents(true, List.of(event)));

            mvc.perform(get(STAFF + "/me/events", org(ORG)).header(AUTH, as(Role.CHECKIN_STAFF, ORG)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.restricted").value(true))
                    .andExpect(jsonPath("$.eventIds[0]").value(event.toString()));
        }

        @Test
        @DisplayName("nhân viên soát vé của tổ chức khác: 404 ở cả đường sự kiện của chính mình")
        void to_chuc_khac() throws Exception {
            mvc.perform(get(STAFF + "/me/events", org(ORG)).header(AUTH, as(Role.CHECKIN_STAFF, OTHER_ORG)))
                    .andExpect(status().isNotFound());
            verifyNoInteractions(staffQueries);
        }

        @Test
        @DisplayName("gửi lại và huỷ trả 204; huỷ lời mời đã kích hoạt trả 409 với mã ổn định")
        void gui_lai_va_huy() throws Exception {
            String token = as(Role.ORG_ADMIN, ORG);
            UUID user = UUID.randomUUID();

            mvc.perform(post(STAFF + "/{user}/invitation/resend", org(ORG), user)
                            .header(AUTH, token))
                    .andExpect(status().isNoContent());
            verify(staffInvitations).resend(ORG, UserId.of(user));

            doThrow(new ApiException(IdentityErrorCode.STAFF_ALREADY_ACTIVATED, "đã kích hoạt"))
                    .when(staffInvitations)
                    .cancel(ORG, UserId.of(user));
            mvc.perform(delete(STAFF + "/{user}/invitation", org(ORG), user).header(AUTH, token))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("STAFF_ALREADY_ACTIVATED"));
        }
    }

    @Nested
    @DisplayName("Open Host Service /internal/**")
    class NoiBo {

        @Test
        @DisplayName("tra membership không cần JWT (bí mật dùng chung chưa cấu hình ở môi trường này)")
        void tra_membership() throws Exception {
            UUID user = UUID.randomUUID();
            when(membershipLookup.resolve(any()))
                    .thenReturn(new MembershipLookup.Principal(UserId.of(user), Map.of(ORG, Role.ORG_ADMIN), false));

            mvc.perform(get("/internal/memberships")
                            .param("idpSubject", "sub-1")
                            .param("email", "a@example.com")
                            .param("sid", "sid-1")
                            .param("issuedAt", "2026-09-08T10:00:00Z"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(user.toString()))
                    .andExpect(jsonPath("$.memberships['" + org(ORG) + "']").value("ORG_ADMIN"))
                    .andExpect(jsonPath("$.superAdmin").value(false));

            ArgumentCaptor<MembershipLookup.Claims> claims = ArgumentCaptor.forClass(MembershipLookup.Claims.class);
            verify(membershipLookup).resolve(claims.capture());
            assertThat(claims.getValue().sessionId()).isEqualTo("sid-1");
            assertThat(claims.getValue().issuedAt()).isEqualTo(Instant.parse("2026-09-08T10:00:00Z"));
        }

        @Test
        @DisplayName("không xác thực được thì trả principal rỗng (200), không phải lỗi")
        void principal_rong() throws Exception {
            when(membershipLookup.resolve(any())).thenReturn(null);

            mvc.perform(get("/internal/memberships").param("idpSubject", "sub-x"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").doesNotExist())
                    .andExpect(jsonPath("$.memberships").isEmpty());
        }

        @Test
        @DisplayName("thiếu idpSubject là 400 của người gọi, không phải 500")
        void thieu_idp_subject() throws Exception {
            mvc.perform(get("/internal/memberships")).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("issuedAt sai định dạng là 400, không phải 500")
        void issued_at_sai() throws Exception {
            mvc.perform(get("/internal/memberships").param("idpSubject", "s").param("issuedAt", "hom-qua"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("địa chỉ liên hệ chỉ ba trường, không lộ idpSubject hay cờ superadmin")
        void lien_he() throws Exception {
            UUID user = UUID.randomUUID();
            when(organizationQueries.contactOf(UserId.of(user)))
                    .thenReturn(new UserContactView(user.toString(), "a@example.com", "A"));

            mvc.perform(get("/internal/users/{id}", user))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("a@example.com"))
                    .andExpect(jsonPath("$.idpSubject").doesNotExist())
                    .andExpect(jsonPath("$.superAdmin").doesNotExist());
        }

        @Test
        @DisplayName("ghi nhật ký hộ: 204, các trường đi nguyên vẹn vào lệnh")
        void ghi_nhat_ky() throws Exception {
            UUID actor = UUID.randomUUID();
            UUID entity = UUID.randomUUID();

            mvc.perform(post("/internal/audit-logs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    """
                                    {"actorUserId":"%s","organizationId":"%s","action":"BUYER_LIST_EXPORTED",
                                     "entityType":"event","entityId":"%s","details":{"rows":3}}
                                    """
                                            .formatted(actor, org(ORG), entity)))
                    .andExpect(status().isNoContent());

            ArgumentCaptor<RecordExternalAuditHandler.Command> command =
                    ArgumentCaptor.forClass(RecordExternalAuditHandler.Command.class);
            verify(recordExternalAudit).handle(command.capture());
            assertThat(command.getValue().actorUserId()).isEqualTo(actor);
            assertThat(command.getValue().organizationId()).isEqualTo(ORG.value());
            assertThat(command.getValue().details()).containsEntry("rows", 3);
        }

        @Test
        @DisplayName("hành động ngoài danh sách: 400 VALIDATION_FAILED dạng RFC 7807")
        void hanh_dong_ngoai_danh_sach() throws Exception {
            doThrow(new ApiException(ErrorCode.Common.VALIDATION_FAILED, "Hành động không được phép ghi từ bên ngoài"))
                    .when(recordExternalAudit)
                    .handle(any());

            mvc.perform(post("/internal/audit-logs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"MEMBER_REMOVED\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("thân hỏng hay id sai định dạng: 400")
        void than_hong() throws Exception {
            mvc.perform(post("/internal/audit-logs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"actorUserId\":\"x\"}"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(recordExternalAudit);
        }
    }
}
