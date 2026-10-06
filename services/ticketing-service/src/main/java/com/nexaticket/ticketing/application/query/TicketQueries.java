// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application.query;

import com.nexaticket.ticketing.domain.model.QrToken;
import com.nexaticket.ticketing.domain.model.Ticket;
import com.nexaticket.ticketing.domain.port.TicketRepository;
import com.nexaticket.ticketing.domain.port.TicketSigner;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ví vé của khách.
 *
 * <p>Token QR được <b>ký lại mỗi lần đọc</b> với hạn ngắn, không lưu sẵn trong database. Nhờ vậy
 * ảnh chụp màn hình vé phát tán trên mạng sẽ hết hiệu lực sau vài giờ, trong khi vé thật thì mở
 * app là có mã mới.
 */
@Service
public class TicketQueries {

    /**
     * Hạn của token QR.
     *
     * <p>Đủ dài để khách mở app ở nhà rồi vào cửa mà không cần mạng, đủ ngắn để một ảnh chụp màn
     * hình bị phát tán không dùng được vào hôm sau.
     */
    private static final Duration TOKEN_TTL = Duration.ofHours(12);

    private final TicketRepository tickets;
    private final TicketSigner signer;
    private final Clock clock;

    public TicketQueries(TicketRepository tickets, TicketSigner signer, Clock clock) {
        this.tickets = tickets;
        this.signer = signer;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TicketView> forUser(UUID userId, int limit, int offset) {
        return tickets.findByUser(userId, limit, offset).stream()
                .map(this::withToken)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TicketView> forOrder(UUID orderId, UUID userId) {
        return tickets.findByOrder(orderId).stream()
                .filter(ticket -> ticket.userId().equals(userId))
                .map(this::withToken)
                .toList();
    }

    private TicketView withToken(Ticket ticket) {
        QrToken token = new QrToken(ticket.id(), clock.instant().plus(TOKEN_TTL).getEpochSecond());
        return TicketView.from(ticket, signer.sign(token));
    }
}
