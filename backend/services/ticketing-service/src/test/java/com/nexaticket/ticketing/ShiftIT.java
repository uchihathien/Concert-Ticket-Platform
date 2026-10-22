// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.security.tenant.TestTenant;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.ticketing.application.CheckinAccess;
import com.nexaticket.ticketing.application.CheckinAssignments;
import com.nexaticket.ticketing.application.CheckinShifts;
import com.nexaticket.ticketing.application.command.CheckInHandler;
import com.nexaticket.ticketing.application.command.IssueTicketsHandler;
import com.nexaticket.ticketing.domain.model.QrToken;
import com.nexaticket.ticketing.domain.port.CatalogPort;
import com.nexaticket.ticketing.domain.port.TicketRepository;
import com.nexaticket.ticketing.domain.port.TicketSigner;
import com.nexaticket.ticketing.infrastructure.crypto.SigningKeyStore;
import com.nexaticket.ticketing.support.TicketingTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Ca trực: xếp ca, bắt đầu / kết thúc ca, và ràng buộc "chỉ quét trong ca" — PostgreSQL thật. */
class ShiftIT extends TicketingTestBase {

    @Autowired
    CheckinShifts shifts;

    @Autowired
    CheckinAccess access;

    @Autowired
    CheckinAssignments assignments;

    @Autowired
    CheckInHandler checkIn;

    @Autowired
    IssueTicketsHandler issueTickets;

    @Autowired
    TicketRepository tickets;

    @Autowired
    TicketSigner signer;

    @Autowired
    SigningKeyStore keys;

    @MockitoBean
    CatalogPort catalog;

    private UUID organizationId;
    private UUID eventId;
    private UUID sessionId;
    private UUID owner;
    private UUID staff;

    @BeforeEach
    void setUp() {
        if (!keys.hasActiveKey()) {
            keys.rotate();
        }
        organizationId = UUID.randomUUID();
        eventId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        owner = UUID.randomUUID();
        staff = UUID.randomUUID();
        Instant showtime = Instant.now().plus(Duration.ofMinutes(30));
        when(catalog.sessionInfo(sessionId))
                .thenReturn(Optional.of(new CatalogPort.SessionInfo(eventId, "Đêm nhạc", showtime, null)));
        when(catalog.sessionsOfEvents(any()))
                .thenReturn(Optional.of(
                        List.of(new CatalogPort.SessionSlot(sessionId, eventId, "Đêm nhạc", showtime, null))));
        assignments.replace(organizationId, staff, List.of(eventId), owner);
    }

    @AfterEach
    void tearDown() {
        TestTenant.clear();
    }

    @Test
    @DisplayName("Nhân viên chỉ quét được trong ca đã bắt đầu của đúng suất; cổng lấy theo ca")
    void chi_quet_trong_ca() {
        var shift = shiftFromNow(Duration.ofMinutes(-10), Duration.ofHours(3), staff);

        asStaff();
        // Chưa bấm "Bắt đầu ca": từ chối, dù đã được gán sự kiện.
        assertThatThrownBy(() -> access.requireScannerOnShift(TenantId.of(organizationId), sessionId, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("chưa bắt đầu ca");

        var started = shifts.startShift(organizationId, shift.id(), "may-1");
        assertThat(started.myStartedAt()).isNotNull();
        // Bấm lần hai là quay lại phiên cũ, không mở phiên mới.
        assertThat(shifts.startShift(organizationId, shift.id(), "may-1").myStartedAt())
                .isEqualTo(started.myStartedAt());

        var scope = access.requireScannerOnShift(TenantId.of(organizationId), sessionId, null);
        assertThat(scope.shiftGate()).isEqualTo("Cổng A");

        // Ca của suất này không mở được quyền ở suất khác.
        UUID otherSession = UUID.randomUUID();
        when(catalog.sessionInfo(otherSession))
                .thenReturn(Optional.of(new CatalogPort.SessionInfo(eventId, "Đêm khác", Instant.now(), null)));
        assertThatThrownBy(() -> access.requireScannerOnShift(TenantId.of(organizationId), otherSession, null))
                .isInstanceOf(ApiException.class);

        // Ban tổ chức thấy ai đang trực ở cổng nào.
        asOwner();
        var view = shifts.shiftsOfEvent(organizationId, eventId).get(0);
        assertThat(view.state()).isEqualTo(CheckinShifts.ShiftState.ACTIVE);
        assertThat(view.onDuty()).extracting(CheckinShifts.OnDuty::userId).containsExactly(staff);
    }

    @Test
    @DisplayName("Quét ngoài ca bị từ chối: ca chưa tới giờ (quá 30 phút sớm), và ca đã kết thúc")
    void ngoai_ca_bi_tu_choi() {
        var later = shiftFromNow(Duration.ofHours(2), Duration.ofHours(4), staff);
        var past = shiftFromNow(Duration.ofHours(-5), Duration.ofHours(-1), staff);

        asStaff();
        assertThatThrownBy(() -> shifts.startShift(organizationId, later.id(), "may-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Chưa tới giờ");
        assertThatThrownBy(() -> shifts.startShift(organizationId, past.id(), "may-1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("đã kết thúc");
        assertThatThrownBy(() -> access.requireScannerOnShift(TenantId.of(organizationId), sessionId, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("Sớm 30 phút thì bắt đầu được; kết thúc ca thì hết quyền quét ngay")
    void som_30_phut_va_ket_thuc() {
        var soon = shiftFromNow(Duration.ofMinutes(20), Duration.ofHours(3), staff);

        asStaff();
        shifts.startShift(organizationId, soon.id(), "may-1");
        assertThat(access.requireScannerOnShift(TenantId.of(organizationId), sessionId, null)
                        .shiftGate())
                .isEqualTo("Cổng A");

        shifts.endShift(organizationId, soon.id());
        assertThatThrownBy(() -> access.requireScannerOnShift(TenantId.of(organizationId), sessionId, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("Người quản lý sự kiện không bị ràng buộc ca")
    void quan_ly_khong_can_ca() {
        asOwner();
        var scope = access.requireScannerOnShift(TenantId.of(organizationId), sessionId, null);
        assertThat(scope.shiftGate()).isNull();
    }

    @Test
    @DisplayName("Kết thúc ca: tổng kết đúng số vào, ra, từ chối của chính nhân viên trong ca")
    void tong_ket_ca() {
        var shift = shiftFromNow(Duration.ofMinutes(-10), Duration.ofHours(3), staff);
        asStaff();
        shifts.startShift(organizationId, shift.id(), "may-1");

        UUID a = issueOne();
        UUID b = issueOne();
        scan(a, "IN", staff);
        scan(b, "IN", staff);
        scan(a, "IN", staff); // đang ở trong → từ chối
        scan(a, "OUT", staff);
        scan(b, "IN", UUID.randomUUID()); // người khác quét — không tính cho ca này

        var handover = shifts.endShift(organizationId, shift.id());

        assertThat(handover.gateName()).isEqualTo("Cổng A");
        assertThat(handover.tally().entries()).isEqualTo(2);
        assertThat(handover.tally().exits()).isEqualTo(1);
        assertThat(handover.tally().rejected()).isEqualTo(1);
        assertThat(handover.tally().overrides()).isZero();
    }

    @Test
    @DisplayName("Không xếp được ca cho người chưa được gán sự kiện ở mục Nhân sự")
    void ca_can_nhan_vien_da_duoc_gan() {
        asOwner();
        var gate = shifts.createGate(organizationId, eventId, "Cổng B");
        var draft = new CheckinShifts.ShiftDraft(
                sessionId,
                gate.id(),
                Instant.now(),
                Instant.now().plus(Duration.ofHours(2)),
                List.of(UUID.randomUUID()));

        assertThatThrownBy(() -> shifts.createShift(organizationId, draft))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("chưa được gán");
        // Cổng còn ca thì không xoá được.
        var used = shiftFromNow(Duration.ZERO, Duration.ofHours(1), staff);
        asOwner();
        assertThatThrownBy(() -> shifts.deleteGate(organizationId, used.gateId()))
                .isInstanceOf(ApiException.class);
    }

    // --- dựng dữ liệu ---

    private CheckinShifts.ShiftView shiftFromNow(Duration start, Duration end, UUID... staffIds) {
        asOwner();
        var gate = shifts.gates(organizationId, eventId).stream()
                .filter(g -> g.name().equals("Cổng A"))
                .findFirst()
                .orElseGet(() -> shifts.createGate(organizationId, eventId, "Cổng A"));
        Instant now = Instant.now();
        return shifts.createShift(
                organizationId,
                new CheckinShifts.ShiftDraft(sessionId, gate.id(), now.plus(start), now.plus(end), List.of(staffIds)));
    }

    private void asOwner() {
        TestTenant.as(owner, organizationId, Role.ORG_OWNER);
    }

    private void asStaff() {
        TestTenant.as(staff, organizationId, Role.CHECKIN_STAFF);
    }

    private UUID issueOne() {
        var command = new IssueTicketsHandler.Command(
                UUID.randomUUID(),
                sessionId,
                organizationId,
                UUID.randomUUID(),
                "Khách Ca",
                List.of(new IssueTicketsHandler.Command.SeatLine(
                        UUID.randomUUID(), UUID.randomUUID(), "B-1", "B", "STANDING", null, "Vé đứng")));
        issueTickets.handle(command);
        return tickets.findByOrder(command.orderId()).get(0).id();
    }

    private void scan(UUID ticketId, String direction, UUID staffId) {
        checkIn.handle(new CheckInHandler.Command(
                signer.sign(new QrToken(ticketId, Instant.now().plusSeconds(600).getEpochSecond())),
                null,
                sessionId,
                staffId,
                organizationId,
                "may-1",
                direction,
                "Cổng A",
                false,
                null));
    }
}
