// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import static com.nexaticket.identity.application.support.Fixtures.CLOCK;
import static com.nexaticket.identity.application.support.Fixtures.NOW;
import static com.nexaticket.identity.application.support.Fixtures.ORG;
import static com.nexaticket.identity.application.support.Fixtures.OTHER_ORG;
import static com.nexaticket.identity.application.support.Fixtures.errorCode;
import static com.nexaticket.identity.application.support.Fixtures.newUser;
import static com.nexaticket.identity.application.support.Fixtures.organization;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.model.Invitation;
import com.nexaticket.identity.domain.model.OrganizationStatus;
import com.nexaticket.identity.domain.port.InvitationRepository;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.outbox.OutboxWriter;
import com.nexaticket.platform.security.tenant.TenantContextFixture;
import com.nexaticket.platform.web.error.ErrorCode;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

/** Mời thành viên bằng mã: quyền ORG_ADMIN+, tổ chức phải đang hoạt động, không cấp vai trò soát vé. */
class InviteMemberHandlerTest {

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final InvitationRepository invitations = mock(InvitationRepository.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final InviteMemberHandler handler =
            new InviteMemberHandler(organizations, invitations, outbox, audit, CLOCK);

    private final UserId admin = newUser();

    @AfterEach
    void clear() {
        TenantContextFixture.clear();
    }

    private void activeOrg() {
        when(organizations.findById(ORG)).thenReturn(Optional.of(organization(ORG, Map.of(admin, Role.ORG_ADMIN))));
    }

    @Test
    @DisplayName("ORG_ADMIN mời được: lưu lời mời hạn 7 ngày, ghi nhật ký và phát member.invited")
    void moi_thanh_cong() {
        activeOrg();
        TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);

        String token = handler.handle(ORG, "Ban.Moi@Example.com", Role.EVENT_MANAGER);

        ArgumentCaptor<Invitation> saved = ArgumentCaptor.forClass(Invitation.class);
        verify(invitations).save(saved.capture());
        assertThat(saved.getValue().organizationId()).isEqualTo(ORG);
        assertThat(saved.getValue().email()).isEqualTo("ban.moi@example.com");
        assertThat(saved.getValue().expiresAt()).isEqualTo(NOW.plus(Invitation.LIFETIME));
        assertThat(Invitation.hash(token)).isEqualTo(saved.getValue().tokenHash());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(outbox)
                .append(
                        eq(CreateOrganizationHandler.EXCHANGE),
                        eq("Invitation"),
                        eq(saved.getValue().id()),
                        eq("member.invited"),
                        payload.capture());
        assertThat(payload.getValue())
                .containsEntry("organizationName", "Tổ chức thử")
                .containsEntry("role", "EVENT_MANAGER")
                .doesNotContainKeys("token", "tokenHash");
        verify(audit)
                .record(
                        eq("MEMBER_INVITED"),
                        eq("invitation"),
                        eq(saved.getValue().id()),
                        any(),
                        any());
    }

    @ParameterizedTest
    @EnumSource(
            value = Role.class,
            names = {"EVENT_MANAGER", "CHECKIN_STAFF"})
    @DisplayName("vai trò dưới ORG_ADMIN không mời được ai")
    void vai_tro_thap_khong_moi_duoc(Role role) {
        TenantContextFixture.member(newUser(), ORG, role);

        assertThatThrownBy(() -> handler.handle(ORG, "x@example.com", Role.EVENT_MANAGER))
                .is(errorCode(ErrorCode.Common.FORBIDDEN));
        verifyNoInteractions(organizations, invitations, outbox, audit);
    }

    @Test
    @DisplayName("ORG_ADMIN của tổ chức khác không mời vào tổ chức này được")
    void admin_to_chuc_khac() {
        TenantContextFixture.member(admin, OTHER_ORG, Role.ORG_ADMIN);

        assertThatThrownBy(() -> handler.handle(ORG, "x@example.com", Role.EVENT_MANAGER))
                .is(errorCode(ErrorCode.Common.FORBIDDEN));
    }

    @Test
    @DisplayName("vai trò soát vé không đi qua lời mời bằng mã — kể cả gọi thẳng API")
    void khong_moi_staff_bang_ma() {
        TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);

        assertThatThrownBy(() -> handler.handle(ORG, "x@example.com", Role.CHECKIN_STAFF))
                .is(errorCode(IdentityErrorCode.STAFF_INVITE_REQUIRED));
        verifyNoInteractions(invitations, outbox);
    }

    @Test
    @DisplayName("tổ chức bị khoá thì không mời được")
    void to_chuc_bi_khoa() {
        when(organizations.findById(ORG))
                .thenReturn(
                        Optional.of(organization(ORG, OrganizationStatus.SUSPENDED, Map.of(admin, Role.ORG_ADMIN))));
        TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);

        assertThatThrownBy(() -> handler.handle(ORG, "x@example.com", Role.EVENT_MANAGER))
                .is(errorCode(IdentityErrorCode.ORGANIZATION_SUSPENDED));
        verify(invitations, never()).save(any());
        verify(outbox, never()).append(anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("tổ chức không tồn tại (superadmin gọi) thì ORGANIZATION_NOT_FOUND")
    void to_chuc_khong_ton_tai() {
        when(organizations.findById(ORG)).thenReturn(Optional.empty());
        TenantContextFixture.superAdmin(newUser());

        assertThatThrownBy(() -> handler.handle(ORG, "x@example.com", Role.EVENT_MANAGER))
                .is(errorCode(IdentityErrorCode.ORGANIZATION_NOT_FOUND));
    }

    @Test
    @DisplayName(
            "ORG_ADMIN không mời được ai vào vai trò ORG_OWNER — đó là đường vòng của luật 'chỉ OWNER phong OWNER'")
    void admin_khong_moi_owner() {
        // ManageMembersHandler chặn ORG_ADMIN phong ORG_OWNER (identity-api.md, luật 3). Lời mời thì
        // không: ORG_ADMIN mời một email mình kiểm soát với vai trò ORG_OWNER, nhận lời mời, và có
        // đúng thứ luật 3 định ngăn — rồi gỡ được cả chủ sở hữu thật.
        activeOrg();
        TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);

        assertThatThrownBy(() -> handler.handle(ORG, "tai-khoan-phu@example.com", Role.ORG_OWNER))
                .is(errorCode(ErrorCode.Common.FORBIDDEN));
        verify(invitations, never()).save(any());
    }

    @Test
    @DisplayName("ORG_OWNER mời được người khác làm ORG_OWNER")
    void owner_moi_owner() {
        activeOrg();
        TenantContextFixture.member(newUser(), ORG, Role.ORG_OWNER);

        assertThat(handler.handle(ORG, "dong-so-huu@example.com", Role.ORG_OWNER))
                .isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(
            value = Role.class,
            names = {"SUPER_ADMIN", "CUSTOMER"})
    @DisplayName("vai trò ngoài phạm vi tổ chức là lỗi 400 nghiệp vụ, không phải IllegalArgumentException (500)")
    void vai_tro_ngoai_pham_vi(Role role) {
        // InviteRequest chỉ kiểm @NotNull. GrantMembershipHandler đã dịch trường hợp này sang
        // VALIDATION_FAILED; InviteMemberHandler thì để Invitation.issue ném IllegalArgumentException.
        activeOrg();
        TenantContextFixture.member(admin, ORG, Role.ORG_ADMIN);

        assertThatThrownBy(() -> handler.handle(ORG, "x@example.com", role))
                .is(errorCode(ErrorCode.Common.VALIDATION_FAILED));
    }
}
