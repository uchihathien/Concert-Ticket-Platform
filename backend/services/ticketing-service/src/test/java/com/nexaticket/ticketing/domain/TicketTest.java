// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.ticketing.domain.model.CheckinResult;
import com.nexaticket.ticketing.domain.model.GateWindow;
import com.nexaticket.ticketing.domain.model.ScanDirection;
import com.nexaticket.ticketing.domain.model.Ticket;
import com.nexaticket.ticketing.domain.model.TicketStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Luật soát vé — thuần domain, không cần database. */
class TicketTest {

    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();

    @Test
    @DisplayName("Vé đúng suất, còn hiệu lực: cho qua")
    void ve_hop_le() {
        assertThat(ticket(TicketStatus.VALID).evaluate(SESSION)).isEqualTo(CheckinResult.ACCEPTED);
    }

    @Test
    @DisplayName("Sai suất diễn được kiểm TRƯỚC trạng thái vé")
    void sai_suat_dien_kiem_truoc() {
        // Sự kiện nhiều đêm: khách cầm vé đêm qua tới đêm nay là tình huống rất thường gặp.
        // Báo "vé của đêm khác" hữu ích hơn nhiều so với "vé đã soát rồi".
        Ticket used = ticket(TicketStatus.CHECKED_IN);
        assertThat(used.evaluate(UUID.randomUUID())).isEqualTo(CheckinResult.WRONG_SESSION);
    }

    @Test
    @DisplayName("Vé đã soát: ALREADY_CHECKED_IN, không phải từ chối chung chung")
    void ve_da_soat() {
        assertThat(ticket(TicketStatus.CHECKED_IN).evaluate(SESSION)).isEqualTo(CheckinResult.ALREADY_CHECKED_IN);
    }

    @Test
    @DisplayName("Quét Ra: chỉ vé đang ở trong mới ra được")
    void quet_ra() {
        assertThat(ticket(TicketStatus.CHECKED_IN).evaluate(SESSION, ScanDirection.OUT, true))
                .isEqualTo(CheckinResult.EXITED);
        assertThat(ticket(TicketStatus.VALID).evaluate(SESSION, ScanDirection.OUT, true))
                .isEqualTo(CheckinResult.NOT_INSIDE);
        assertThat(outside().evaluate(SESSION, ScanDirection.OUT, true)).isEqualTo(CheckinResult.NOT_INSIDE);
    }

    @Test
    @DisplayName("Vé đã ra ngoài: vào lại được, trừ khi cấu hình không cho")
    void vao_lai() {
        assertThat(outside().evaluate(SESSION, ScanDirection.IN, true)).isEqualTo(CheckinResult.ACCEPTED);
        assertThat(outside().evaluate(SESSION, ScanDirection.IN, false)).isEqualTo(CheckinResult.REENTRY_NOT_ALLOWED);
        // Tắt vào lại không được chặn lần vào ĐẦU TIÊN.
        assertThat(ticket(TicketStatus.VALID).evaluate(SESSION, ScanDirection.IN, false))
                .isEqualTo(CheckinResult.ACCEPTED);
    }

    @Test
    @DisplayName("Vé thu hồi bị chặn ở cả hai hướng")
    void ve_thu_hoi_ca_hai_huong() {
        assertThat(ticket(TicketStatus.REVOKED).evaluate(SESSION, ScanDirection.OUT, true))
                .isEqualTo(CheckinResult.REVOKED);
    }

    @Test
    @DisplayName("Cửa mở trước giờ diễn một khoảng, đóng khi suất kết thúc")
    void khung_gio_mo_cua() {
        Instant start = Instant.parse("2026-10-12T13:00:00Z");
        GateWindow window = GateWindow.of(start, start.plusSeconds(3 * 3600), Duration.ofHours(2), Duration.ofHours(6));

        assertThat(window.check(start.minusSeconds(2 * 3600 + 1))).isEqualTo(CheckinResult.NOT_OPEN_YET);
        assertThat(window.check(start.minusSeconds(2 * 3600))).isNull();
        assertThat(window.check(start.plusSeconds(3 * 3600))).isNull();
        assertThat(window.check(start.plusSeconds(3 * 3600 + 1))).isEqualTo(CheckinResult.SESSION_ENDED);
        // Suất không khai giờ kết thúc vẫn phải đóng cửa: không thì vé đêm qua vào được đêm nay.
        assertThat(GateWindow.of(start, null, Duration.ofHours(2), Duration.ofHours(6))
                        .check(start.plusSeconds(6 * 3600 + 1)))
                .isEqualTo(CheckinResult.SESSION_ENDED);
    }

    @Test
    @DisplayName("Vé đã thu hồi: REVOKED")
    void ve_da_thu_hoi() {
        assertThat(ticket(TicketStatus.REVOKED).evaluate(SESSION)).isEqualTo(CheckinResult.REVOKED);
    }

    @Test
    @DisplayName("Vé của tổ chức khác không thuộc về nhân viên đang quét")
    void ve_to_chuc_khac() {
        assertThat(ticket(TicketStatus.VALID).belongsToOrganization(UUID.randomUUID()))
                .isFalse();
        assertThat(ticket(TicketStatus.VALID).belongsToOrganization(ORG)).isTrue();
    }

    /** Vé đã vào một lần rồi quét Ra. */
    private static Ticket outside() {
        Instant entered = Instant.now().minusSeconds(600);
        return ticket(TicketStatus.CHECKED_IN).withPresence(false, 1, entered, "Cổng A", entered.plusSeconds(300));
    }

    private static Ticket ticket(TicketStatus status) {
        return Ticket.rehydrate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                SESSION,
                ORG,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "A-1",
                "A",
                "SEATED",
                "1",
                "Ve ngoi",
                "Nguyễn Văn A",
                status,
                status == TicketStatus.CHECKED_IN ? Instant.now() : null,
                null);
    }
}
