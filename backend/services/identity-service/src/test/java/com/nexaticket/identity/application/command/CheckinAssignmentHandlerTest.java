// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import static com.nexaticket.identity.application.support.Fixtures.ORG;
import static com.nexaticket.identity.application.support.Fixtures.errorCode;
import static com.nexaticket.identity.application.support.Fixtures.newUser;
import static com.nexaticket.identity.application.support.Fixtures.organization;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.port.CheckinAssignmentPort;
import com.nexaticket.identity.domain.port.CheckinAssignmentPort.Target;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.TenantContextFixture;
import com.nexaticket.platform.web.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Gán sự kiện soát vé: chỉ cho thành viên vai Staff, và lỗi của ticketing phải nói đúng chuyện. */
class CheckinAssignmentHandlerTest {

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final CheckinAssignmentPort assignments = mock(CheckinAssignmentPort.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final CheckinAssignmentHandler handler = new CheckinAssignmentHandler(organizations, assignments, audit);

    private final UserId admin = newUser();
    private final UserId manager = newUser();
    private final UserId staff = newUser();
    private final UUID event = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();

    @BeforeEach
    void stub() {
        Map<UserId, Role> members = new LinkedHashMap<>();
        members.put(admin, Role.ORG_OWNER);
        members.put(manager, Role.EVENT_MANAGER);
        members.put(staff, Role.CHECKIN_STAFF);
        when(organizations.findById(ORG)).thenReturn(Optional.of(organization(ORG, members)));
    }

    @AfterEach
    void clear() {
        TenantContextFixture.clear();
    }

    @Test
    @DisplayName("gán cả sự kiện và một suất riêng cho Staff: chuyển đúng các mục sang ticketing, có người gán")
    void gan_cho_staff() {
        TenantContextFixture.member(admin, ORG, Role.ORG_OWNER);

        handler.assign(
                ORG,
                staff,
                List.of(
                        new CheckinAssignmentHandler.Item(event, null),
                        new CheckinAssignmentHandler.Item(event, session),
                        new CheckinAssignmentHandler.Item(event, null)));

        verify(assignments).assign(ORG, staff, List.of(new Target(event, null), new Target(event, session)), admin);
        verify(audit).record(eq("CHECKIN_ASSIGNMENTS_CHANGED"), eq("user"), eq(staff.value()), eq(null), any());
    }

    @Test
    @DisplayName("gán cho người không phải Staff bị từ chối, ticketing không bị gọi")
    void khong_phai_staff() {
        TenantContextFixture.member(admin, ORG, Role.ORG_OWNER);

        assertThatThrownBy(() -> handler.assign(ORG, manager, List.of(new CheckinAssignmentHandler.Item(event, null))))
                .is(errorCode(IdentityErrorCode.NOT_A_MEMBER));
        assertThatThrownBy(
                        () -> handler.assign(ORG, newUser(), List.of(new CheckinAssignmentHandler.Item(event, null))))
                .is(errorCode(IdentityErrorCode.NOT_A_MEMBER));
        verifyNoInteractions(assignments);
    }

    @Test
    @DisplayName("Staff không tự gán cho mình được")
    void staff_khong_tu_gan() {
        TenantContextFixture.member(staff, ORG, Role.CHECKIN_STAFF);

        assertThatThrownBy(() -> handler.assign(ORG, staff, List.of(new CheckinAssignmentHandler.Item(event, null))))
                .is(errorCode(ErrorCode.Common.FORBIDDEN));
        verifyNoInteractions(assignments);
    }

    @Test
    @DisplayName("ticketing từ chối (sự kiện không thuộc tổ chức) thì 400 kèm lời giải thích của nó")
    void ticketing_tu_choi() {
        TenantContextFixture.member(admin, ORG, Role.ORG_OWNER);
        doThrow(new CheckinAssignmentPort.AssignmentRejectedException(
                        "EVENT_NOT_IN_ORGANIZATION", "Sự kiện không thuộc tổ chức này"))
                .when(assignments)
                .assign(any(), any(), any(), any());

        assertThatThrownBy(() -> handler.assign(ORG, staff, List.of(new CheckinAssignmentHandler.Item(event, null))))
                .is(errorCode(ErrorCode.Common.VALIDATION_FAILED))
                .hasMessageContaining("không thuộc tổ chức");
    }

    @Test
    @DisplayName("ticketing không trả lời thì 503 CHECKIN_ASSIGNMENTS_UNAVAILABLE")
    void ticketing_im_lang() {
        TenantContextFixture.member(admin, ORG, Role.ORG_OWNER);
        doThrow(new CheckinAssignmentPort.AssignmentsUnavailableException("down", null))
                .when(assignments)
                .assign(any(), any(), any(), any());

        assertThatThrownBy(() -> handler.assign(ORG, staff, List.of()))
                .is(errorCode(IdentityErrorCode.CHECKIN_ASSIGNMENTS_UNAVAILABLE));
    }
}
