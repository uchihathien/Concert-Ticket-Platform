// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application.query;

import com.nexaticket.ticketing.domain.port.CheckinLogRepository;
import com.nexaticket.ticketing.domain.port.TicketRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lịch sử quét và số khách của một suất — màn hình của nhân viên soát vé.
 *
 * <p>Chỉ gồm những vé <b>đã được quét</b> ở suất này. Đây không phải đường tra danh sách khách của
 * cả sự kiện (xem {@code TicketingAccess}): nhân viên ở cửa thấy lại những gì đã đi qua cửa của
 * mình, không hơn.
 */
@Service
public class CheckinHistoryQuery {

    private static final int MAX_PAGE_SIZE = 100;

    private final CheckinLogRepository log;
    private final TicketRepository tickets;

    public CheckinHistoryQuery(CheckinLogRepository log, TicketRepository tickets) {
        this.log = log;
        this.tickets = tickets;
    }

    /**
     * @param inside số khách đang ở trong khu vực
     * @param checkedIn số vé đã vào ít nhất một lần
     */
    public record History(int inside, int checkedIn, List<Row> rows, int page, int size) {}

    /**
     * @param ticketCode 8 ký tự đầu của id vé — đúng mã in trên vé của khách; {@code null} khi lần
     *     quét không dẫn tới vé nào
     */
    public record Row(
            UUID id,
            String ticketCode,
            Instant scannedAt,
            String direction,
            String result,
            String method,
            String gate,
            String note,
            String holderName,
            String seatCode,
            String seatLabel,
            String ticketTypeName) {}

    @Transactional(readOnly = true)
    public History forSession(UUID organizationId, UUID eventSessionId, String query, int page, int size) {
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        var presence = tickets.presenceOf(organizationId, eventSessionId);
        List<Row> rows = log.history(organizationId, eventSessionId, query, safeSize, safePage * safeSize).stream()
                .map(e -> new Row(
                        e.id(),
                        e.ticketId() == null
                                ? null
                                : e.ticketId().toString().substring(0, 8).toUpperCase(),
                        e.scannedAt(),
                        e.direction(),
                        e.result(),
                        e.method(),
                        e.gate(),
                        e.note(),
                        e.holderName(),
                        e.seatCode(),
                        e.seatLabel(),
                        e.ticketTypeName()))
                .toList();
        return new History(presence.inside(), presence.checkedIn(), rows, safePage, safeSize);
    }
}
