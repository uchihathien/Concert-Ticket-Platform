// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.ticketing.domain.model.CheckinResult;
import com.nexaticket.ticketing.domain.model.Ticket;
import com.nexaticket.ticketing.domain.model.TicketStatus;
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
                status,
                status == TicketStatus.CHECKED_IN ? Instant.now() : null,
                null);
    }
}
