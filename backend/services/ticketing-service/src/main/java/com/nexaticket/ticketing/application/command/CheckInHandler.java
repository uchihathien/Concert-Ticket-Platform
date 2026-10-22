// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application.command;

import com.nexaticket.ticketing.domain.model.CheckinResult;
import com.nexaticket.ticketing.domain.model.EntryMethod;
import com.nexaticket.ticketing.domain.model.GateWindow;
import com.nexaticket.ticketing.domain.model.QrToken;
import com.nexaticket.ticketing.domain.model.ScanDirection;
import com.nexaticket.ticketing.domain.model.Ticket;
import com.nexaticket.ticketing.domain.port.CatalogPort;
import com.nexaticket.ticketing.domain.port.CheckinLogRepository;
import com.nexaticket.ticketing.domain.port.TicketEventsPort;
import com.nexaticket.ticketing.domain.port.TicketRepository;
import com.nexaticket.ticketing.domain.port.TicketSigner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Soát vé tại cửa: vào, ra, vào lại.
 *
 * <p>Thứ tự kiểm là cố ý, từ rẻ tới đắt và từ ít tin cậy tới nhiều tin cậy:
 *
 * <ol>
 *   <li>Chữ ký và hạn của token — không chạm database, loại luôn ảnh chụp màn hình cũ và vé giả.
 *   <li>Tra vé theo {@code jti}.
 *   <li>Nhân viên có thuộc tổ chức sở hữu vé không — <b>trước</b> khi trả về bất kỳ nhãn nào.
 *   <li>Đúng suất diễn, vé chưa bị thu hồi.
 *   <li>Cửa đã mở và suất chưa kết thúc (chỉ với lượt Vào).
 *   <li>Trong/ngoài, rồi một câu {@code UPDATE ... WHERE inside = ...} nguyên tử.
 * </ol>
 *
 * <p>Bước 6 là bước quyết định: {@link Ticket#evaluate} chỉ <i>dự đoán</i> kết quả, còn thứ thực
 * sự quyết định ai được vào là câu UPDATE có điều kiện. Hai máy quét cùng một vé trong cùng một
 * giây là chuyện bình thường ở cửa, và đọc-rồi-ghi sẽ cho cả hai cùng qua.
 */
@Service
public class CheckInHandler {

    /** Mã vé gõ tay: phần đầu của id vé, tối thiểu 8 ký tự — đúng độ dài app khách in ra. */
    private static final Pattern TICKET_CODE = Pattern.compile("[0-9a-fA-F-]{8,36}");

    private final TicketRepository tickets;
    private final CheckinLogRepository log;
    private final TicketSigner signer;
    private final CatalogPort catalog;
    private final TicketEventsPort events;
    private final Clock clock;
    private final boolean reentryAllowed;
    private final Duration gatesOpenBefore;
    private final Duration fallbackDuration;

    @SuppressWarnings("java:S107") // Ba tham số cuối là cấu hình; gom thành một kiểu chỉ đổi chỗ đặt.
    public CheckInHandler(
            TicketRepository tickets,
            CheckinLogRepository log,
            TicketSigner signer,
            CatalogPort catalog,
            TicketEventsPort events,
            Clock clock,
            @Value("${nexaticket.ticketing.checkin.reentry-allowed:true}") boolean reentryAllowed,
            @Value("${nexaticket.ticketing.checkin.gates-open-before:PT2H}") Duration gatesOpenBefore,
            @Value("${nexaticket.ticketing.checkin.fallback-session-duration:PT6H}") Duration fallbackDuration) {
        this.tickets = tickets;
        this.log = log;
        this.signer = signer;
        this.catalog = catalog;
        this.events = events;
        this.clock = clock;
        this.reentryAllowed = reentryAllowed;
        this.gatesOpenBefore = gatesOpenBefore;
        this.fallbackDuration = fallbackDuration;
    }

    /**
     * @param qrToken mã QR vừa quét; {@code null} khi nhân viên gõ {@code ticketCode}
     * @param ticketCode mã vé gõ tay vì QR hỏng — đi qua cùng bộ kiểm tra, trừ chữ ký
     * @param eventSessionId suất diễn mà máy soát vé đang phục vụ
     * @param staffOrganizationId tổ chức của nhân viên, lấy từ token đăng nhập chứ không từ request
     * @param direction {@code IN} hoặc {@code OUT}; bỏ trống là Vào. Chuỗi chứ không phải enum của
     *     domain, cùng lý do với {@link Result#result}
     * @param override cho vào thủ công dù vé đang được ghi nhận là ở trong; người gọi phải đã kiểm
     *     quyền
     * @param reason lý do cho vào thủ công, bắt buộc khi {@code override}
     * @param lookupTicketId vé nhân viên chọn sau khi tìm khách theo tên / email / SĐT và đối chiếu
     *     giấy tờ — thay cho mã QR và mã gõ tay
     * @param scannedAt lúc máy quét — chỉ có với lượt quét offline đồng bộ muộn; {@code null} là
     *     "bây giờ". Mọi phép kiểm giờ (hạn mã, giờ mở cổng) và mốc vào/ra đều theo giá trị này
     * @param offline lượt quét lúc máy mất mạng, gửi lên sau
     * @param clientScanId id máy tự sinh cho lượt quét offline; gửi lại cùng id thì trả kết quả cũ
     * @param deviceResult kết quả máy đã hiện cho khách lúc quét offline
     */
    public record Command(
            String qrToken,
            String ticketCode,
            UUID eventSessionId,
            UUID staffId,
            UUID staffOrganizationId,
            String deviceId,
            String direction,
            String gate,
            boolean override,
            String reason,
            UUID lookupTicketId,
            Instant scannedAt,
            boolean offline,
            UUID clientScanId,
            String deviceResult) {

        /** Lượt quét trực tuyến — mọi trường offline / tra cứu để trống. */
        public Command(
                String qrToken,
                String ticketCode,
                UUID eventSessionId,
                UUID staffId,
                UUID staffOrganizationId,
                String deviceId,
                String direction,
                String gate,
                boolean override,
                String reason) {
            this(
                    qrToken,
                    ticketCode,
                    eventSessionId,
                    staffId,
                    staffOrganizationId,
                    deviceId,
                    direction,
                    gate,
                    override,
                    reason,
                    null,
                    null,
                    false,
                    null,
                    null);
        }

        /** Quét Vào bằng mã QR — lượt quét của máy soát vé đời đầu. */
        public Command(String qrToken, UUID eventSessionId, UUID staffId, UUID staffOrganizationId, String deviceId) {
            this(
                    qrToken,
                    null,
                    eventSessionId,
                    staffId,
                    staffOrganizationId,
                    deviceId,
                    ScanDirection.IN.name(),
                    null,
                    false,
                    null);
        }
    }

    /**
     * Lượt quét offline đã được ghi trước đó với cùng {@code clientScanId}: trả lại đúng kết quả cũ,
     * không chạy lại gì.
     */
    private Optional<Result> replayed(Command cmd, ScanDirection direction) {
        if (cmd.clientScanId() == null) {
            return Optional.empty();
        }
        return log.findByClientScanId(cmd.clientScanId()).map(previous -> {
            Optional<Ticket> ticket =
                    previous.ticketId() == null ? Optional.empty() : tickets.findById(previous.ticketId());
            return new Result(
                    previous.result(),
                    direction.name(),
                    ticket.map(Ticket::seatCode).orElse(null),
                    ticket.map(Ticket::seatLabel).orElse(null),
                    ticket.map(Ticket::ticketTypeName).orElse(null),
                    previous.note(),
                    previous.ticketId(),
                    ticket.map(Ticket::holderName).orElse(null),
                    ticket.map(Ticket::zoneCode).orElse(null),
                    ticket.map(Ticket::entryCount).orElse(0),
                    ticket.map(Ticket::lastEntryAt).orElse(null),
                    ticket.map(Ticket::lastEntryGate).orElse(null),
                    ticket.map(Ticket::eventSessionId).orElse(null),
                    previous.conflict(),
                    true);
        });
    }

    /**
     * Mọi nhãn ở đây do SERVER trả về sau khi đã kiểm quyền, không phải đọc từ mã QR (mã QR cố ý
     * không chứa thông tin gì).
     *
     * @param result tên kết quả dạng chuỗi, không phải enum của domain: controller không được phụ
     *     thuộc vào domain (tactical-ddd.md §7), và giá trị này là hợp đồng ổn định mà máy soát vé
     *     rẽ nhánh giao diện theo đó
     * @param entryCount số lần vé đã vào cửa, tính cả lần này nếu được cho vào
     * @param lastEntryAt lần vào gần nhất — thứ nhân viên đọc cho khách khi vé "đang ở trong"
     * @param ticketSessionId suất diễn của vé; khác suất đang soát khi kết quả là WRONG_SESSION
     * @param conflict lượt quét offline mà máy đã cho vào nhưng server không đồng ý
     * @param duplicate lượt offline đã được ghi từ lần gửi trước — kết quả cũ, không ghi lại
     */
    public record Result(
            String result,
            String direction,
            String seatCode,
            String seatLabel,
            String ticketTypeName,
            String note,
            UUID ticketId,
            String holderName,
            String zoneCode,
            int entryCount,
            Instant lastEntryAt,
            String lastEntryGate,
            UUID ticketSessionId,
            boolean conflict,
            boolean duplicate) {

        public boolean accepted() {
            return CheckinResult.ACCEPTED.name().equals(result);
        }
    }

    @Transactional
    public Result handle(Command cmd) {
        ScanDirection direction =
                ScanDirection.OUT.name().equals(cmd.direction()) ? ScanDirection.OUT : ScanDirection.IN;
        Optional<Result> again = replayed(cmd, direction);
        if (again.isPresent()) {
            return again.get();
        }
        boolean byLookup = cmd.lookupTicketId() != null;
        boolean byCode = !byLookup && isBlank(cmd.qrToken()) && !isBlank(cmd.ticketCode());
        EntryMethod method = cmd.override()
                ? EntryMethod.OVERRIDE
                : byLookup ? EntryMethod.LOOKUP : byCode ? EntryMethod.CODE : EntryMethod.QR;
        Instant now = cmd.scannedAt() != null ? cmd.scannedAt() : clock.instant();
        Scan scan = new Scan(cmd, direction, method, now);

        Optional<Ticket> found;
        if (byLookup) {
            found = tickets.findById(cmd.lookupTicketId());
        } else if (byCode) {
            String code = cmd.ticketCode().trim();
            if (!TICKET_CODE.matcher(code).matches()) {
                return scan.reject(null, CheckinResult.NOT_FOUND, "Mã vé gồm ít nhất 8 ký tự in trên vé");
            }
            List<Ticket> matches = tickets.findByCodePrefix(cmd.staffOrganizationId(), code);
            if (matches.size() > 1) {
                return scan.reject(null, CheckinResult.NOT_FOUND, "Mã trùng nhiều vé, hãy nhập thêm ký tự");
            }
            found = matches.stream().findFirst();
        } else {
            Optional<QrToken> token = isBlank(cmd.qrToken()) ? Optional.empty() : signer.verify(cmd.qrToken(), now);
            if (token.isEmpty()) {
                return scan.reject(null, CheckinResult.INVALID_TOKEN, "Mã không hợp lệ hoặc đã hết hạn");
            }
            found = tickets.findById(token.get().jti());
        }
        if (found.isEmpty()) {
            return scan.reject(null, CheckinResult.NOT_FOUND, "Không tìm thấy vé");
        }

        Ticket ticket = found.get();

        // Kiểm quyền TRƯỚC khi trả về nhãn ghế: nếu không, ai có một token hợp lệ bất kỳ cũng
        // dò được thông tin vé của tổ chức khác.
        if (!ticket.belongsToOrganization(cmd.staffOrganizationId())) {
            log.record(scan.entry(ticket.id(), CheckinResult.WRONG_SESSION, "Vé không thuộc tổ chức của nhân viên"));
            return scan.anonymous(CheckinResult.WRONG_SESSION, "Vé không thuộc sự kiện của bạn");
        }

        CheckinResult verdict = ticket.evaluate(cmd.eventSessionId(), direction, reentryAllowed);
        if (verdict == CheckinResult.WRONG_SESSION || verdict == CheckinResult.REVOKED) {
            return scan.reject(ticket, verdict, noteFor(verdict));
        }

        Optional<CatalogPort.SessionInfo> session = catalog.sessionInfo(cmd.eventSessionId());

        // Chỉ lượt Vào bị giờ diễn chặn. Khách ra về sau khi suất kết thúc vẫn phải quét Ra được,
        // nếu không số "đang ở trong" sẽ không bao giờ về 0.
        if (direction == ScanDirection.IN && session.isPresent()) {
            CheckinResult closed = GateWindow.of(
                            session.get().startsAt(), session.get().endsAt(), gatesOpenBefore, fallbackDuration)
                    .check(now);
            if (closed != null) {
                return scan.reject(ticket, closed, noteFor(closed));
            }
        }

        if (cmd.override() && verdict == CheckinResult.ALREADY_CHECKED_IN) {
            if (isBlank(cmd.reason())) {
                return scan.reject(ticket, verdict, "Cho vào thủ công cần ghi rõ lý do");
            }
            Optional<Integer> forced = tickets.forceEnter(ticket.id(), now, cmd.gate());
            if (forced.isPresent()) {
                log.record(scan.entry(
                        ticket.id(), CheckinResult.ACCEPTED, cmd.reason().trim()));
                return scan.result(ticket, CheckinResult.ACCEPTED, "Cho vào thủ công", forced.get(), now, cmd.gate());
            }
            // Giữa lúc đọc và lúc ghi vé đã được quét Ra: không còn gì để ghi đè, quét Vào bình
            // thường là đủ.
            verdict = CheckinResult.ACCEPTED;
        }

        if (!verdict.succeeded()) {
            return scan.reject(ticket, verdict, noteFor(verdict));
        }

        if (direction == ScanDirection.OUT) {
            // Thứ THỰC SỰ quyết định: thua cuộc đua nghĩa là máy khác vừa ghi nhận ra rồi.
            CheckinResult exited = tickets.exit(ticket.id(), now) ? CheckinResult.EXITED : CheckinResult.NOT_INSIDE;
            log.record(scan.entry(ticket.id(), exited, exited.succeeded() ? null : noteFor(exited)));
            return scan.result(
                    ticket,
                    exited,
                    exited.succeeded() ? null : noteFor(exited),
                    ticket.entryCount(),
                    ticket.lastEntryAt(),
                    ticket.lastEntryGate());
        }

        // Thứ THỰC SỰ quyết định ai được vào.
        Optional<Integer> entered = tickets.enter(ticket.id(), cmd.staffId(), now, cmd.gate(), reentryAllowed);
        if (entered.isEmpty()) {
            // Thua cuộc đua: máy khác vừa cho vé này vào. Lần vào đó là "ngay lúc này".
            log.record(scan.entry(
                    ticket.id(), CheckinResult.ALREADY_CHECKED_IN, noteFor(CheckinResult.ALREADY_CHECKED_IN)));
            return scan.result(
                    ticket,
                    CheckinResult.ALREADY_CHECKED_IN,
                    noteFor(CheckinResult.ALREADY_CHECKED_IN),
                    ticket.entryCount() + 1,
                    now,
                    null);
        }

        int entryCount = entered.get();
        if (entryCount == 1) {
            // Chỉ lần vào ĐẦU TIÊN mới báo cho khách. Ghi vào outbox trong cùng transaction: nhân
            // viên nhận kết quả ngay, còn việc gửi thư là của notification-service.
            events.checkedIn(
                    ticket,
                    now,
                    cmd.gate(),
                    session.map(CatalogPort.SessionInfo::eventTitle).orElse(null));
        }
        log.record(scan.entry(ticket.id(), CheckinResult.ACCEPTED, null));
        return scan.result(
                ticket,
                CheckinResult.ACCEPTED,
                entryCount > 1 ? "Vào lại lần " + (entryCount - 1) : null,
                entryCount,
                now,
                cmd.gate());
    }

    private static String noteFor(CheckinResult verdict) {
        return switch (verdict) {
            case ALREADY_CHECKED_IN -> "Vé đang ở trong khu vực";
            case NOT_INSIDE -> "Vé chưa vào hoặc đã ra ngoài";
            case NOT_OPEN_YET -> "Chưa tới giờ mở cổng";
            case SESSION_ENDED -> "Suất diễn đã kết thúc";
            case REENTRY_NOT_ALLOWED -> "Sự kiện không cho phép vào lại";
            case WRONG_SESSION -> "Vé thuộc suất diễn khác";
            case REVOKED -> "Vé đã bị huỷ hoặc hoàn tiền";
            default -> null;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Một lượt quét đang xử lý: gom những gì mọi dòng nhật ký và mọi kết quả đều cần. */
    private final class Scan {

        private final Command cmd;
        private final ScanDirection direction;
        private final EntryMethod method;
        private final Instant at;

        private Scan(Command cmd, ScanDirection direction, EntryMethod method, Instant at) {
            this.cmd = cmd;
            this.direction = direction;
            this.method = method;
            this.at = at;
        }

        /**
         * Xung đột offline: máy đã cho khách VÀO (khách đã ở trong rồi) mà server không đồng ý —
         * máy khác vừa cho cùng vé vào, hoặc vé bị thu hồi lúc máy mất mạng.
         */
        private boolean conflicts(CheckinResult result) {
            return cmd.offline()
                    && CheckinResult.ACCEPTED.name().equals(cmd.deviceResult())
                    && result != CheckinResult.ACCEPTED;
        }

        private CheckinLogRepository.Scan entry(UUID ticketId, CheckinResult result, String note) {
            return new CheckinLogRepository.Scan(
                    ticketId,
                    cmd.eventSessionId(),
                    cmd.staffOrganizationId(),
                    cmd.staffId(),
                    cmd.deviceId(),
                    direction,
                    cmd.gate(),
                    method,
                    result,
                    note,
                    cmd.scannedAt() == null ? null : at,
                    cmd.offline(),
                    cmd.clientScanId(),
                    cmd.deviceResult(),
                    conflicts(result));
        }

        /** Từ chối kèm nhãn vé (nếu đã xác định được vé và nhân viên có quyền xem). */
        private Result reject(Ticket ticket, CheckinResult result, String note) {
            log.record(entry(ticket == null ? null : ticket.id(), result, note));
            if (ticket == null) {
                return anonymous(result, note);
            }
            return result(ticket, result, note, ticket.entryCount(), ticket.lastEntryAt(), ticket.lastEntryGate());
        }

        /** Kết quả không lộ bất kỳ thông tin nào của vé. */
        private Result anonymous(CheckinResult result, String note) {
            return new Result(
                    result.name(),
                    direction.name(),
                    null,
                    null,
                    null,
                    note,
                    null,
                    null,
                    null,
                    0,
                    null,
                    null,
                    null,
                    conflicts(result),
                    false);
        }

        private Result result(
                Ticket ticket,
                CheckinResult result,
                String note,
                int entryCount,
                Instant lastEntryAt,
                String lastEntryGate) {
            return new Result(
                    result.name(),
                    direction.name(),
                    ticket.seatCode(),
                    ticket.seatLabel(),
                    ticket.ticketTypeName(),
                    note,
                    ticket.id(),
                    ticket.holderName(),
                    ticket.zoneCode(),
                    entryCount,
                    lastEntryAt,
                    lastEntryGate,
                    ticket.eventSessionId(),
                    conflicts(result),
                    false);
        }
    }
}
