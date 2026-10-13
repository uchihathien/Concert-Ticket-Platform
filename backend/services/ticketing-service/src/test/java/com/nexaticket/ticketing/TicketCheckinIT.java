// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.ticketing.application.CheckinAccess;
import com.nexaticket.ticketing.application.CheckinAssignments;
import com.nexaticket.ticketing.application.command.CheckInHandler;
import com.nexaticket.ticketing.application.command.IssueTicketsHandler;
import com.nexaticket.ticketing.application.query.CheckinHistoryQuery;
import com.nexaticket.ticketing.domain.model.CheckinResult;
import com.nexaticket.ticketing.domain.model.QrToken;
import com.nexaticket.ticketing.domain.port.CatalogPort;
import com.nexaticket.ticketing.domain.port.TicketRepository;
import com.nexaticket.ticketing.domain.port.TicketSigner;
import com.nexaticket.ticketing.infrastructure.crypto.SigningKeyStore;
import com.nexaticket.ticketing.support.TicketingTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Phát hành vé và soát vé tại cửa, với PostgreSQL thật. */
class TicketCheckinIT extends TicketingTestBase {

    @Autowired
    IssueTicketsHandler issueTickets;

    @Autowired
    CheckInHandler checkIn;

    @Autowired
    TicketRepository tickets;

    @Autowired
    TicketSigner signer;

    @Autowired
    SigningKeyStore keys;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CheckinHistoryQuery history;

    @Autowired
    CheckinAccess access;

    @Autowired
    CheckinAssignments assignments;

    /**
     * Mặc định trả rỗng — "không đọc được giờ diễn" — nên các test không nói gì về giờ vẫn chạy
     * như trước. Test nào cần giờ diễn thì tự khai.
     */
    @MockitoBean
    CatalogPort catalog;

    private UUID sessionId;
    private UUID organizationId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        if (!keys.hasActiveKey()) {
            keys.rotate();
        }
        sessionId = UUID.randomUUID();
        organizationId = UUID.randomUUID();
        userId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Phát hành hai lần cho cùng một đơn: lần hai không tạo vé nào")
    void phat_hanh_idempotent() {
        // order.paid có thể đến hai lần: webhook payOS retry, consumer chạy lại, RabbitMQ
        // giao lại. Cả ba đường đều phải dừng ở tickets.order_item_id UNIQUE.
        var command = issueCommand(3);

        assertThat(issueTickets.handle(command)).isEqualTo(3);
        assertThat(issueTickets.handle(command)).isZero();
        assertThat(tickets.findByOrder(command.orderId())).hasSize(3);
    }

    @Test
    @DisplayName("Soát vé hợp lệ: cho qua, và server trả nhãn ghế để hiện lên máy")
    void soat_ve_hop_le() {
        var ticket = issueOne();

        var result = checkIn.handle(scan(ticket, sessionId, organizationId));

        assertThat(result.result()).isEqualTo(CheckinResult.ACCEPTED.name());
        // Nhãn ghế do SERVER trả về sau khi đã kiểm quyền, không phải đọc từ mã QR.
        assertThat(result.seatCode()).isEqualTo("A-1");
    }

    @Test
    @DisplayName("Quét lại vé đã soát: ALREADY_CHECKED_IN kèm giờ đã soát")
    void quet_lai_ve_da_soat() {
        var ticket = issueOne();
        checkIn.handle(scan(ticket, sessionId, organizationId));

        var again = checkIn.handle(scan(ticket, sessionId, organizationId));

        assertThat(again.result()).isEqualTo(CheckinResult.ALREADY_CHECKED_IN.name());
        // Nhân viên phải đọc được cho khách lần vào trước là lúc nào, ở cổng nào.
        assertThat(again.note()).contains("đang ở trong");
        assertThat(again.lastEntryAt()).isNotNull();
        assertThat(again.lastEntryGate()).isEqualTo("Cổng A");
    }

    @Test
    @DisplayName("Vào → ra → vào lại: đếm đúng số lần vào, và chỉ MỘT sự kiện check-in cho vé")
    void vao_ra_vao_lai() {
        var ticket = issueOne();

        var first = checkIn.handle(scan(ticket, "IN"));
        var out = checkIn.handle(scan(ticket, "OUT"));
        var outAgain = checkIn.handle(scan(ticket, "OUT"));
        var back = checkIn.handle(scan(ticket, "IN"));

        assertThat(first.result()).isEqualTo(CheckinResult.ACCEPTED.name());
        assertThat(first.entryCount()).isEqualTo(1);
        assertThat(first.holderName()).isEqualTo("Nguyễn Văn A");
        assertThat(out.result()).isEqualTo(CheckinResult.EXITED.name());
        assertThat(outAgain.result()).isEqualTo(CheckinResult.NOT_INSIDE.name());
        assertThat(back.result()).isEqualTo(CheckinResult.ACCEPTED.name());
        assertThat(back.entryCount()).isEqualTo(2);
        // Khách chỉ nhận thư ở lần vào đầu tiên; ra ngoài và vào lại không phát thêm sự kiện nào.
        assertThat(checkedInEvents(ticket)).isEqualTo(1);
        // Màn tra cứu của ban tổ chức đọc checked_in_at là lần vào ĐẦU TIÊN.
        var stored = tickets.findById(ticket).orElseThrow();
        assertThat(stored.checkedInAt()).isBefore(stored.lastEntryAt());
        assertThat(stored.inside()).isTrue();
    }

    @Test
    @DisplayName("Quét Ra khi chưa vào: NOT_INSIDE, vé vẫn vào được bình thường")
    void ra_khi_chua_vao() {
        var ticket = issueOne();

        assertThat(checkIn.handle(scan(ticket, "OUT")).result()).isEqualTo(CheckinResult.NOT_INSIDE.name());
        assertThat(checkIn.handle(scan(ticket, "IN")).result()).isEqualTo(CheckinResult.ACCEPTED.name());
    }

    @Test
    @DisplayName("Chưa tới giờ mở cổng: NOT_OPEN_YET, vé không bị đánh dấu đã dùng")
    void chua_toi_gio_mo_cong() {
        var ticket = issueOne();
        when(catalog.sessionInfo(sessionId))
                .thenReturn(Optional.of(new CatalogPort.SessionInfo(
                        UUID.randomUUID(), "Đêm nhạc", Instant.now().plus(Duration.ofHours(5)), null)));

        var result = checkIn.handle(scan(ticket, "IN"));

        assertThat(result.result()).isEqualTo(CheckinResult.NOT_OPEN_YET.name());
        assertThat(tickets.findById(ticket).orElseThrow().entryCount()).isZero();
    }

    @Test
    @DisplayName("Suất đã kết thúc: không cho vào nữa, nhưng khách đang ở trong vẫn quét Ra được")
    void suat_da_ket_thuc() {
        var inside = issueOne();
        var late = issueOne();
        checkIn.handle(scan(inside, "IN"));
        when(catalog.sessionInfo(sessionId))
                .thenReturn(Optional.of(new CatalogPort.SessionInfo(
                        UUID.randomUUID(),
                        "Đêm nhạc",
                        Instant.now().minus(Duration.ofHours(4)),
                        Instant.now().minus(Duration.ofHours(1)))));

        assertThat(checkIn.handle(scan(late, "IN")).result()).isEqualTo(CheckinResult.SESSION_ENDED.name());
        // Chặn cả lượt Ra thì số "đang ở trong" không bao giờ về 0.
        assertThat(checkIn.handle(scan(inside, "OUT")).result()).isEqualTo(CheckinResult.EXITED.name());
    }

    @Test
    @DisplayName("QR hỏng: gõ mã vé đi qua cùng bộ kiểm tra, và nhật ký ghi rõ là gõ tay")
    void nhap_ma_ve_thu_cong() {
        var ticket = issueOne();
        String code = ticket.toString().substring(0, 8).toUpperCase();

        var result = checkIn.handle(command(null, code, "IN", false, null));
        var again = checkIn.handle(command(null, code, "IN", false, null));

        assertThat(result.result()).isEqualTo(CheckinResult.ACCEPTED.name());
        assertThat(again.result()).isEqualTo(CheckinResult.ALREADY_CHECKED_IN.name());
        assertThat(countMethod(ticket, "CODE")).isEqualTo(2);
    }

    @Test
    @DisplayName("Mã gõ tay của tổ chức khác: NOT_FOUND, không lộ gì")
    void ma_ve_to_chuc_khac() {
        var ticket = issueOne();
        String code = ticket.toString().substring(0, 8);

        var result = checkIn.handle(new CheckInHandler.Command(
                null, code, sessionId, UUID.randomUUID(), UUID.randomUUID(), "device-1", "IN", null, false, null));

        assertThat(result.result()).isEqualTo(CheckinResult.NOT_FOUND.name());
        assertThat(result.holderName()).isNull();
    }

    @Test
    @DisplayName("Cho vào thủ công khi vé đang ở trong: cần lý do, và lý do nằm trong nhật ký")
    void cho_vao_thu_cong() {
        var ticket = issueOne();
        checkIn.handle(scan(ticket, "IN"));
        String token = token(ticket);

        var noReason = checkIn.handle(command(token, null, "IN", true, "  "));
        var forced = checkIn.handle(command(token, null, "IN", true, "Khách ra hút thuốc, quên quét Ra"));

        assertThat(noReason.result()).isEqualTo(CheckinResult.ALREADY_CHECKED_IN.name());
        assertThat(forced.result()).isEqualTo(CheckinResult.ACCEPTED.name());
        assertThat(forced.entryCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT note FROM checkin_log WHERE ticket_id = ? AND entry_method = 'OVERRIDE' AND result = 'ACCEPTED'",
                        String.class,
                        ticket))
                .isEqualTo("Khách ra hút thuốc, quên quét Ra");
        // Cho vào thủ công không phải lần check-in đầu tiên: không phát thêm sự kiện.
        assertThat(checkedInEvents(ticket)).isEqualTo(1);
    }

    @Test
    @DisplayName("Lịch sử quét của suất: có cả lần bị từ chối, tìm được theo tên, kèm số đang ở trong")
    void lich_su_quet() {
        var inside = issueOne();
        var left = issueOne();
        checkIn.handle(scan(inside, "IN"));
        checkIn.handle(scan(inside, "IN"));
        checkIn.handle(scan(left, "IN"));
        checkIn.handle(scan(left, "OUT"));
        checkIn.handle(new CheckInHandler.Command("token.gia.mao", sessionId, UUID.randomUUID(), organizationId, "d"));

        var all = history.forSession(organizationId, sessionId, null, 0, 50);
        var byName = history.forSession(organizationId, sessionId, "văn a", 0, 50);
        var otherOrg = history.forSession(UUID.randomUUID(), sessionId, null, 0, 50);

        assertThat(all.inside()).isEqualTo(1);
        assertThat(all.checkedIn()).isEqualTo(2);
        assertThat(all.rows()).hasSize(5);
        assertThat(all.rows())
                .extracting(CheckinHistoryQuery.Row::result)
                .contains("ACCEPTED", "ALREADY_CHECKED_IN", "EXITED", "INVALID_TOKEN");
        // Lần quét mã giả không gắn với vé nào nên không khớp tên ai.
        assertThat(byName.rows()).hasSize(4);
        assertThat(otherOrg.rows()).isEmpty();
        assertThat(otherOrg.checkedIn()).isZero();
    }

    @Test
    @DisplayName("Hai máy quét cùng một vé cùng lúc: đúng MỘT máy cho qua")
    void hai_may_quet_cung_luc() throws Exception {
        // Chuyện bình thường ở cửa vào: khách đưa điện thoại cho hai nhân viên, hoặc một
        // nhân viên bấm hai lần. Đọc-rồi-ghi sẽ cho cả hai cùng thấy VALID và cùng cho qua.
        var ticket = issueOne();

        int racers = 8;
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<String>> jobs = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            jobs.add(() -> {
                ready.countDown();
                go.await();
                return checkIn.handle(scan(ticket, sessionId, organizationId)).result();
            });
        }

        List<String> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> job : jobs) {
                futures.add(pool.submit(job));
            }
            ready.await();
            go.countDown();
            for (Future<String> future : futures) {
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    results.add(null);
                }
            }
        }

        assertThat(results)
                .filteredOn(r -> CheckinResult.ACCEPTED.name().equals(r))
                .hasSize(1);
    }

    @Test
    @DisplayName("Vé của suất diễn khác: WRONG_SESSION")
    void ve_suat_dien_khac() {
        var ticket = issueOne();

        var result = checkIn.handle(scan(ticket, UUID.randomUUID(), organizationId));

        assertThat(result.result()).isEqualTo(CheckinResult.WRONG_SESSION.name());
        // Vé vẫn còn nguyên hiệu lực cho đúng suất của nó.
        assertThat(checkIn.handle(scan(ticket, sessionId, organizationId)).result())
                .isEqualTo(CheckinResult.ACCEPTED.name());
    }

    @Test
    @DisplayName("Nhân viên tổ chức khác quét: bị từ chối và KHÔNG lộ nhãn ghế")
    void nhan_vien_to_chuc_khac() {
        var ticket = issueOne();

        var result = checkIn.handle(scan(ticket, sessionId, UUID.randomUUID()));

        assertThat(result.result()).isEqualTo(CheckinResult.WRONG_SESSION.name());
        // Có một token hợp lệ bất kỳ mà dò được thông tin vé của tổ chức khác là rò rỉ dữ liệu.
        assertThat(result.seatCode()).isNull();
    }

    @Test
    @DisplayName("Token giả: INVALID_TOKEN, và vẫn được ghi vào nhật ký soát vé")
    void token_gia() {
        var result = checkIn.handle(
                new CheckInHandler.Command("token.gia.mao", sessionId, UUID.randomUUID(), organizationId, "device-1"));

        assertThat(result.result()).isEqualTo(CheckinResult.INVALID_TOKEN.name());
        assertThat(countLog(CheckinResult.INVALID_TOKEN)).isPositive();
    }

    @Test
    @DisplayName("Chữ ký hợp lệ nhưng không có vé nào mang mã đó: NOT_FOUND")
    void chu_ky_dung_nhung_khong_co_ve() {
        String orphan = signer.sign(
                new QrToken(UUID.randomUUID(), Instant.now().plusSeconds(600).getEpochSecond()));

        var result = checkIn.handle(
                new CheckInHandler.Command(orphan, sessionId, UUID.randomUUID(), organizationId, "device-1"));

        assertThat(result.result()).isEqualTo(CheckinResult.NOT_FOUND.name());
    }

    @Test
    @DisplayName("Thu hồi vé khi hoàn tiền, nhưng KHÔNG động tới vé đã soát")
    void thu_hoi_ve_khong_dong_toi_ve_da_soat() {
        var command = issueCommand(2);
        issueTickets.handle(command);
        var all = tickets.findByOrder(command.orderId());
        checkIn.handle(scan(all.get(0).id(), sessionId, organizationId));

        int revoked = tickets.revokeByOrder(command.orderId(), "Hoàn tiền", Instant.now());

        // Người đã vào cửa rồi; đổi trạng thái vé của họ sẽ xoá mất bằng chứng đó.
        assertThat(revoked).isEqualTo(1);
        assertThat(checkIn.handle(scan(all.get(1).id(), sessionId, organizationId))
                        .result())
                .isEqualTo(CheckinResult.REVOKED.name());
    }

    // --- dựng dữ liệu ---

    @Test
    @DisplayName("Nhân viên soát vé chỉ soát được sự kiện đã được gán; người quản lý tổ chức thì không cần gán")
    void chi_soat_su_kien_duoc_gan() {
        UUID staff = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID otherSession = UUID.randomUUID();
        when(catalog.sessionInfo(sessionId))
                .thenReturn(Optional.of(new CatalogPort.SessionInfo(eventId, "Đêm nhạc", Instant.now(), null)));
        when(catalog.sessionInfo(otherSession))
                .thenReturn(
                        Optional.of(new CatalogPort.SessionInfo(UUID.randomUUID(), "Đêm khác", Instant.now(), null)));

        // Chưa gán: từ chối, kể cả khi app có để lọt màn hình quét.
        assertThatThrownBy(() -> access.requireAssigned(false, organizationId, staff, sessionId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("chưa được gán");

        assignments.replace(organizationId, staff, List.of(eventId), UUID.randomUUID());

        assertThatCode(() -> access.requireAssigned(false, organizationId, staff, sessionId))
                .doesNotThrowAnyException();
        // Được gán sự kiện này không có nghĩa là soát được sự kiện khác của cùng tổ chức...
        assertThatThrownBy(() -> access.requireAssigned(false, organizationId, staff, otherSession))
                .isInstanceOf(ApiException.class);
        // ...cũng không mang được việc gán sang tổ chức khác.
        assertThatThrownBy(() -> access.requireAssigned(false, UUID.randomUUID(), staff, sessionId))
                .isInstanceOf(ApiException.class);
        // Người quản lý sự kiện của tổ chức soát mọi suất của tổ chức, không cần dòng gán nào.
        assertThatCode(() -> access.requireAssigned(true, organizationId, UUID.randomUUID(), otherSession))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Gỡ nhân viên khỏi sự kiện: mất quyền ngay ở lượt quét kế tiếp")
    void go_khoi_su_kien_mat_quyen_ngay() {
        UUID staff = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        UUID removed = UUID.randomUUID();
        UUID assigner = UUID.randomUUID();
        when(catalog.sessionInfo(sessionId))
                .thenReturn(Optional.of(new CatalogPort.SessionInfo(removed, "Đêm nhạc", Instant.now(), null)));
        assignments.replace(organizationId, staff, List.of(kept, removed), assigner);

        var after = assignments.replace(organizationId, staff, List.of(kept), assigner);
        // Gửi lại đúng danh sách cũ không được tạo trùng hay đổi gì.
        assignments.replace(organizationId, staff, List.of(kept, kept), assigner);

        assertThat(after).extracting(CheckinAssignments.Assigned::eventId).containsExactly(kept);
        assertThat(assignments.list(organizationId, staff)).hasSize(1);
        assertThatThrownBy(() -> access.requireAssigned(false, organizationId, staff, sessionId))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("Catalog không trả lời: nhân viên KHÔNG được cho qua bước kiểm quyền")
    void catalog_im_lang_thi_khong_cho_qua() {
        UUID staff = UUID.randomUUID();
        // `catalog` mặc định trả rỗng. Bước kiểm giờ thì bỏ qua được, bước kiểm quyền thì không.
        assertThatThrownBy(() -> access.requireAssigned(false, organizationId, staff, sessionId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Chưa xác minh được");
    }

    private IssueTicketsHandler.Command issueCommand(int seats) {
        List<IssueTicketsHandler.Command.SeatLine> lines = new ArrayList<>();
        for (int i = 1; i <= seats; i++) {
            lines.add(new IssueTicketsHandler.Command.SeatLine(
                    UUID.randomUUID(), UUID.randomUUID(), "A-" + i, "A", "SEATED", String.valueOf(i), "Ve ngoi"));
        }
        return new IssueTicketsHandler.Command(
                UUID.randomUUID(), sessionId, organizationId, userId, "Nguyễn Văn A", lines);
    }

    private UUID issueOne() {
        var command = issueCommand(1);
        issueTickets.handle(command);
        return tickets.findByOrder(command.orderId()).get(0).id();
    }

    private CheckInHandler.Command scan(UUID ticketId, UUID scanningSession, UUID staffOrg) {
        return new CheckInHandler.Command(
                token(ticketId),
                null,
                scanningSession,
                UUID.randomUUID(),
                staffOrg,
                "device-1",
                "IN",
                "Cổng A",
                false,
                null);
    }

    private CheckInHandler.Command scan(UUID ticketId, String direction) {
        return command(token(ticketId), null, direction, false, null);
    }

    private CheckInHandler.Command command(
            String qrToken, String ticketCode, String direction, boolean override, String reason) {
        return new CheckInHandler.Command(
                qrToken,
                ticketCode,
                sessionId,
                UUID.randomUUID(),
                organizationId,
                "device-1",
                direction,
                "Cổng A",
                override,
                reason);
    }

    private String token(UUID ticketId) {
        return signer.sign(new QrToken(ticketId, Instant.now().plusSeconds(600).getEpochSecond()));
    }

    private int checkedInEvents(UUID ticketId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE event_type = 'ticket.checked_in' AND aggregate_id = ?",
                Integer.class,
                ticketId);
        return n == null ? 0 : n;
    }

    private int countMethod(UUID ticketId, String method) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM checkin_log WHERE ticket_id = ? AND entry_method = ?",
                Integer.class,
                ticketId,
                method);
        return n == null ? 0 : n;
    }

    private int countLog(CheckinResult result) {
        Integer n =
                jdbc.queryForObject("SELECT COUNT(*) FROM checkin_log WHERE result = ?", Integer.class, result.name());
        return n == null ? 0 : n;
    }
}
