// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.ticketing.application.command.CheckInHandler;
import com.nexaticket.ticketing.application.command.IssueTicketsHandler;
import com.nexaticket.ticketing.domain.model.CheckinResult;
import com.nexaticket.ticketing.domain.model.QrToken;
import com.nexaticket.ticketing.domain.port.TicketRepository;
import com.nexaticket.ticketing.domain.port.TicketSigner;
import com.nexaticket.ticketing.infrastructure.crypto.SigningKeyStore;
import com.nexaticket.ticketing.support.TicketingTestBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
        // order.paid có thể đến hai lần: webhook SePay retry, consumer chạy lại, RabbitMQ
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
        assertThat(again.note()).contains("Đã soát");
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

    private IssueTicketsHandler.Command issueCommand(int seats) {
        List<IssueTicketsHandler.Command.SeatLine> lines = new ArrayList<>();
        for (int i = 1; i <= seats; i++) {
            lines.add(new IssueTicketsHandler.Command.SeatLine(
                    UUID.randomUUID(), UUID.randomUUID(), "A-" + i, "A", "SEATED", String.valueOf(i), "Ve ngoi"));
        }
        return new IssueTicketsHandler.Command(UUID.randomUUID(), sessionId, organizationId, userId, lines);
    }

    private UUID issueOne() {
        var command = issueCommand(1);
        issueTickets.handle(command);
        return tickets.findByOrder(command.orderId()).get(0).id();
    }

    private CheckInHandler.Command scan(UUID ticketId, UUID scanningSession, UUID staffOrg) {
        String token =
                signer.sign(new QrToken(ticketId, Instant.now().plusSeconds(600).getEpochSecond()));
        return new CheckInHandler.Command(token, scanningSession, UUID.randomUUID(), staffOrg, "device-1");
    }

    private int countLog(CheckinResult result) {
        Integer n =
                jdbc.queryForObject("SELECT COUNT(*) FROM checkin_log WHERE result = ?", Integer.class, result.name());
        return n == null ? 0 : n;
    }
}
