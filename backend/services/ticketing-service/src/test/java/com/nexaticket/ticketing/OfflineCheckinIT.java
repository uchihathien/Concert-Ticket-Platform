// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.nexaticket.kernel.access.Role;
import com.nexaticket.platform.security.tenant.TestTenant;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.ticketing.application.CheckinAssignments;
import com.nexaticket.ticketing.application.CheckinShifts;
import com.nexaticket.ticketing.application.OfflineCheckin;
import com.nexaticket.ticketing.application.command.IssueTicketsHandler;
import com.nexaticket.ticketing.domain.model.QrToken;
import com.nexaticket.ticketing.domain.port.CatalogPort;
import com.nexaticket.ticketing.domain.port.IdentityPort;
import com.nexaticket.ticketing.domain.port.TicketRepository;
import com.nexaticket.ticketing.domain.port.TicketSigner;
import com.nexaticket.ticketing.infrastructure.crypto.SigningKeyStore;
import com.nexaticket.ticketing.support.TicketingTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Soát vé offline (đồng bộ lô, gửi trùng, xung đột) và tìm khách quên vé — PostgreSQL thật. */
class OfflineCheckinIT extends TicketingTestBase {

    @Autowired
    OfflineCheckin offline;

    @Autowired
    CheckinShifts shifts;

    @Autowired
    CheckinAssignments assignments;

    @Autowired
    IssueTicketsHandler issueTickets;

    @Autowired
    TicketRepository tickets;

    @Autowired
    TicketSigner signer;

    @Autowired
    SigningKeyStore keys;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    CatalogPort catalog;

    @MockitoBean
    IdentityPort identity;

    private UUID organizationId;
    private UUID eventId;
    private UUID sessionId;
    private UUID owner;
    private UUID staff;
    private UUID buyer;

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
        buyer = UUID.randomUUID();
        Instant showtime = Instant.now().plus(Duration.ofMinutes(30));
        when(catalog.sessionInfo(sessionId))
                .thenReturn(Optional.of(new CatalogPort.SessionInfo(eventId, "Đêm nhạc", showtime, null)));
        when(catalog.sessionsOfEvents(any()))
                .thenReturn(Optional.of(
                        List.of(new CatalogPort.SessionSlot(sessionId, eventId, "Đêm nhạc", showtime, null))));
        when(identity.contactsOf(any()))
                .thenReturn(Optional.of(List.of(
                        new IdentityPort.Contact(buyer, "nguyenvana@gmail.com", "Nguyễn Văn A", "0901 234 567"))));
        assignments.replace(organizationId, staff, List.of(eventId), owner);
    }

    @AfterEach
    void tearDown() {
        TestTenant.clear();
    }

    @Test
    @DisplayName("Đồng bộ lô: chạy lại đúng logic soát vé theo thứ tự quét, đánh dấu offline, tính vào tổng kết ca")
    void dong_bo_lo() {
        UUID shift = startShift();
        UUID a = issueOne();
        UUID b = issueOne();
        // Cắt về micro giây: PostgreSQL lưu micro giây, còn Instant.now() trên Windows có độ phân giải 100 ns —
        // so sánh nguyên văn đỏ trên Windows và xanh trên Linux/macOS.
        Instant t0 =
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).minus(Duration.ofMinutes(3));

        // Gửi lệch thứ tự: server phải xếp lại theo thời điểm quét (vào trước, ra sau).
        var result = offline.sync(
                organizationId,
                sessionId,
                List.of(
                        scan(a, "OUT", t0.plusSeconds(60), "EXITED"),
                        scan(a, "IN", t0, "ACCEPTED"),
                        scan(b, "IN", t0.plusSeconds(10), "ACCEPTED")));

        assertThat(result.scans())
                .extracting(OfflineCheckin.SyncedScan::result)
                .containsExactly("ACCEPTED", "ACCEPTED", "EXITED");
        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.conflicts()).isZero();
        assertThat(tickets.findById(a).orElseThrow().inside()).isFalse();
        assertThat(tickets.findById(b).orElseThrow().inside()).isTrue();
        // Mốc vào là lúc máy quét, không phải lúc gửi lên.
        assertThat(tickets.findById(b).orElseThrow().lastEntryAt()).isEqualTo(t0.plusSeconds(10));
        assertThat(countLog("offline")).isEqualTo(3);

        var handover = shifts.endShift(organizationId, shift);
        assertThat(handover.tally().offline()).isEqualTo(3);
        assertThat(handover.tally().entries()).isEqualTo(2);
    }

    @Test
    @DisplayName("Gửi trùng cùng clientScanId: trả kết quả cũ, không ghi lần hai")
    void gui_trung() {
        startShift();
        UUID a = issueOne();
        var batch = List.of(scan(a, "IN", Instant.now().minusSeconds(30), "ACCEPTED"));

        offline.sync(organizationId, sessionId, batch);
        var again = offline.sync(organizationId, sessionId, batch);

        assertThat(again.scans().get(0).duplicate()).isTrue();
        assertThat(again.scans().get(0).result()).isEqualTo("ACCEPTED");
        assertThat(again.accepted()).isZero();
        assertThat(countLog("offline")).isEqualTo(1);
        assertThat(tickets.findById(a).orElseThrow().entryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Hai máy offline cùng cho một vé vào: ghi cả hai, lượt sau là xung đột")
    void xung_dot_hai_may() {
        startShift();
        UUID a = issueOne();
        Instant t0 = Instant.now().minus(Duration.ofMinutes(2));

        var first = offline.sync(organizationId, sessionId, List.of(scan(a, "IN", t0, "ACCEPTED", "may-1")));
        var second = offline.sync(
                organizationId, sessionId, List.of(scan(a, "IN", t0.plusSeconds(20), "ACCEPTED", "may-2")));

        assertThat(first.scans().get(0).conflict()).isFalse();
        assertThat(second.scans().get(0).result()).isEqualTo("ALREADY_CHECKED_IN");
        assertThat(second.scans().get(0).conflict()).isTrue();
        assertThat(second.conflicts()).isEqualTo(1);
        assertThat(countLog("ticket_id = '" + a + "'")).isEqualTo(2);
        assertThat(offline.conflicts(organizationId, sessionId))
                .singleElement()
                .satisfies(row -> assertThat(row.result()).isEqualTo("ALREADY_CHECKED_IN"));
    }

    @Test
    @DisplayName("Vé bị thu hồi trong lúc máy offline: server từ chối, đánh dấu xung đột")
    void ve_thu_hoi_khi_offline() {
        startShift();
        var order = issue();
        UUID a = tickets.findByOrder(order).get(0).id();
        tickets.revokeByOrder(order, "Hoàn tiền", Instant.now().minus(Duration.ofMinutes(5)));

        var result = offline.sync(
                organizationId, sessionId, List.of(scan(a, "IN", Instant.now().minusSeconds(60), "ACCEPTED")));

        assertThat(result.scans().get(0).result()).isEqualTo("REVOKED");
        assertThat(result.scans().get(0).conflict()).isTrue();
    }

    @Test
    @DisplayName("Mã còn hạn lúc quét nhưng hết hạn lúc gửi: vẫn hợp lệ — kiểm theo thời điểm quét")
    void ma_het_han_luc_gui() {
        startShift();
        UUID a = issueOne();
        Instant scannedAt = Instant.now().minus(Duration.ofMinutes(2));
        String token = signer.sign(new QrToken(a, Instant.now().minusSeconds(30).getEpochSecond()));

        var result = offline.sync(
                organizationId,
                sessionId,
                List.of(new OfflineCheckin.OfflineScan(
                        UUID.randomUUID(), token, null, null, "IN", scannedAt, "may-1", "ACCEPTED")));

        assertThat(result.scans().get(0).result()).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("Lượt offline ngoài ca: không được ghi như lượt soát hợp lệ, máy đã cho vào thì là xung đột")
    void offline_ngoai_ca() {
        asStaff(); // được gán sự kiện nhưng KHÔNG có ca nào
        UUID a = issueOne();

        var result = offline.sync(
                organizationId, sessionId, List.of(scan(a, "IN", Instant.now().minusSeconds(20), "ACCEPTED")));

        assertThat(result.scans().get(0).result()).isEqualTo("SHIFT_NOT_ACTIVE");
        assertThat(result.scans().get(0).conflict()).isTrue();
        assertThat(tickets.findById(a).orElseThrow().entryCount()).isZero();
    }

    @Test
    @DisplayName("Gói dữ liệu: khoá công khai thô 32 byte, vé kèm liên lạc ĐÃ CHE; tải bù chỉ phần đổi")
    void goi_du_lieu() {
        startShift();
        UUID a = issueOne();

        var bundle = offline.bundle(organizationId, sessionId, null);

        assertThat(bundle.full()).isTrue();
        assertThat(bundle.keys()).isNotEmpty();
        assertThat(Base64.getUrlDecoder().decode(bundle.keys().get(0).publicKey()))
                .hasSize(32);
        var row = bundle.tickets().stream()
                .filter(t -> t.ticketId().equals(a))
                .findFirst()
                .orElseThrow();
        assertThat(row.code()).isEqualTo(a.toString().substring(0, 8).toUpperCase());
        assertThat(row.emailMasked()).isEqualTo("ng***@gmail.com");
        assertThat(row.phoneMasked()).isEqualTo("*******567");
        assertThat(row.status()).isEqualTo("VALID");

        // Không có gì đổi sau mốc serverTime: lần tải bù rỗng.
        var delta = offline.bundle(organizationId, sessionId, bundle.serverTime());
        assertThat(delta.full()).isFalse();
        assertThat(delta.tickets()).isEmpty();
    }

    @Test
    @DisplayName("Tìm khách theo tên không dấu, email, SĐT; cho vào cần đối chiếu giấy tờ và đi qua đủ kiểm tra")
    void tim_khach_va_cho_vao() {
        startShift();
        UUID a = issueOne();

        assertThat(offline.search(organizationId, sessionId, "nguyen van"))
                .extracting(OfflineCheckin.TicketRow::ticketId)
                .contains(a);
        assertThat(offline.search(organizationId, sessionId, "nguyenvana@")).isNotEmpty();
        assertThat(offline.search(organizationId, sessionId, "234567")).isNotEmpty();
        assertThat(offline.search(organizationId, sessionId, "x")).isEmpty();
        assertThat(offline.search(organizationId, sessionId, "nguyen").get(0).emailMasked())
                .doesNotContain("nguyenvana");

        assertThatThrownBy(() -> offline.admitByLookup(organizationId, sessionId, a, false, "may-1"))
                .isInstanceOf(ApiException.class);

        var first = offline.admitByLookup(organizationId, sessionId, a, true, "may-1");
        var again = offline.admitByLookup(organizationId, sessionId, a, true, "may-1");

        assertThat(first.result()).isEqualTo("ACCEPTED");
        // Tra cứu không vượt được "đang ở trong" — khác cho vào thủ công.
        assertThat(again.result()).isEqualTo("ALREADY_CHECKED_IN");
        assertThat(countLog("entry_method = 'LOOKUP'")).isEqualTo(2);
    }

    // --- dựng dữ liệu ---

    /** Xếp một ca đang diễn ra cho nhân viên và bấm bắt đầu; để người đăng nhập là nhân viên. */
    private UUID startShift() {
        TestTenant.as(owner, organizationId, Role.ORG_OWNER);
        var gate = shifts.createGate(organizationId, eventId, "Cổng A");
        Instant now = Instant.now();
        var shift = shifts.createShift(
                organizationId,
                new CheckinShifts.ShiftDraft(
                        sessionId,
                        gate.id(),
                        now.minus(Duration.ofMinutes(20)),
                        now.plus(Duration.ofHours(3)),
                        List.of(staff)));
        asStaff();
        shifts.startShift(organizationId, shift.id(), "may-1");
        // Phiên ca bắt đầu "bây giờ"; lùi nó về đầu ca để các lượt quét vài phút trước vẫn nằm trong.
        jdbc.update(
                "UPDATE checkin_shift_sessions SET started_at = ? WHERE shift_id = ?",
                java.sql.Timestamp.from(now.minus(Duration.ofMinutes(20))),
                shift.id());
        return shift.id();
    }

    private void asStaff() {
        TestTenant.as(staff, organizationId, Role.CHECKIN_STAFF);
    }

    private UUID issue() {
        var command = new IssueTicketsHandler.Command(
                UUID.randomUUID(),
                sessionId,
                organizationId,
                buyer,
                "Nguyễn Văn A",
                List.of(new IssueTicketsHandler.Command.SeatLine(
                        UUID.randomUUID(), UUID.randomUUID(), "C-1", "C", "STANDING", null, "Vé đứng")));
        issueTickets.handle(command);
        return command.orderId();
    }

    private UUID issueOne() {
        return tickets.findByOrder(issue()).get(0).id();
    }

    private OfflineCheckin.OfflineScan scan(UUID ticket, String direction, Instant at, String deviceResult) {
        return scan(ticket, direction, at, deviceResult, "may-1");
    }

    private OfflineCheckin.OfflineScan scan(
            UUID ticket, String direction, Instant at, String deviceResult, String device) {
        String token =
                signer.sign(new QrToken(ticket, Instant.now().plusSeconds(600).getEpochSecond()));
        return new OfflineCheckin.OfflineScan(
                UUID.randomUUID(), token, null, null, direction, at, device, deviceResult);
    }

    private int countLog(String where) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM checkin_log WHERE event_session_id = ? AND " + where, Integer.class, sessionId);
        return n == null ? 0 : n;
    }
}
