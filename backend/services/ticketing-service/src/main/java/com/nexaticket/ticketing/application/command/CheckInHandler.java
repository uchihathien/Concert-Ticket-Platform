// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application.command;

import com.nexaticket.ticketing.domain.model.CheckinResult;
import com.nexaticket.ticketing.domain.model.QrToken;
import com.nexaticket.ticketing.domain.model.Ticket;
import com.nexaticket.ticketing.domain.port.CheckinLogRepository;
import com.nexaticket.ticketing.domain.port.TicketRepository;
import com.nexaticket.ticketing.domain.port.TicketSigner;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Soát vé tại cửa.
 *
 * <p>Thứ tự kiểm là cố ý, từ rẻ tới đắt và từ ít tin cậy tới nhiều tin cậy:
 *
 * <ol>
 *   <li>Chữ ký và hạn của token — không chạm database, loại luôn ảnh chụp màn hình cũ và vé giả.
 *   <li>Tra vé theo {@code jti}.
 *   <li>Nhân viên có thuộc tổ chức sở hữu vé không — <b>trước</b> khi trả về bất kỳ nhãn nào.
 *   <li>Trạng thái vé và đúng suất diễn.
 *   <li>{@code UPDATE ... WHERE status = 'VALID'} nguyên tử.
 * </ol>
 *
 * <p>Bước 5 là bước quyết định: {@link Ticket#evaluate} chỉ <i>dự đoán</i> kết quả, còn thứ thực
 * sự quyết định ai được vào là câu UPDATE có điều kiện. Hai máy quét cùng một vé trong cùng một
 * giây là chuyện bình thường ở cửa, và đọc-rồi-ghi sẽ cho cả hai cùng qua.
 */
@Service
public class CheckInHandler {

    private final TicketRepository tickets;
    private final CheckinLogRepository log;
    private final TicketSigner signer;
    private final Clock clock;

    public CheckInHandler(TicketRepository tickets, CheckinLogRepository log, TicketSigner signer, Clock clock) {
        this.tickets = tickets;
        this.log = log;
        this.signer = signer;
        this.clock = clock;
    }

    /**
     * @param eventSessionId suất diễn mà máy soát vé đang phục vụ
     * @param staffOrganizationId tổ chức của nhân viên, lấy từ token đăng nhập chứ không từ request
     */
    public record Command(
            String qrToken, UUID eventSessionId, UUID staffId, UUID staffOrganizationId, String deviceId) {}

    /**
     * @param seatLabel nhãn chỗ để hiện lên màn hình máy soát — do SERVER trả về sau khi đã kiểm
     *     quyền, không phải đọc từ mã QR (mã QR cố ý không chứa thông tin gì)
     */
    /**
     * @param result tên kết quả dạng chuỗi, không phải enum của domain: controller không được
     *     phụ thuộc vào domain (tactical-ddd.md §7), và giá trị này là hợp đồng ổn định mà
     *     web-scanner rẽ nhánh giao diện theo đó
     */
    public record Result(String result, String seatCode, String seatLabel, String ticketTypeName, String note) {

        public boolean accepted() {
            return CheckinResult.ACCEPTED.name().equals(result);
        }
    }

    @Transactional
    public Result handle(Command cmd) {
        Optional<QrToken> token = signer.verify(cmd.qrToken());
        if (token.isEmpty()) {
            log.record(null, cmd.eventSessionId(), cmd.staffId(), cmd.deviceId(), CheckinResult.INVALID_TOKEN, null);
            return reject(CheckinResult.INVALID_TOKEN, "Mã không hợp lệ hoặc đã hết hạn");
        }

        Optional<Ticket> found = tickets.findById(token.get().jti());
        if (found.isEmpty()) {
            log.record(null, cmd.eventSessionId(), cmd.staffId(), cmd.deviceId(), CheckinResult.NOT_FOUND, null);
            return reject(CheckinResult.NOT_FOUND, "Không tìm thấy vé");
        }

        Ticket ticket = found.get();

        // Kiểm quyền TRƯỚC khi trả về nhãn ghế: nếu không, ai có một token hợp lệ bất kỳ cũng
        // dò được thông tin vé của tổ chức khác.
        if (!ticket.belongsToOrganization(cmd.staffOrganizationId())) {
            log.record(
                    ticket.id(),
                    cmd.eventSessionId(),
                    cmd.staffId(),
                    cmd.deviceId(),
                    CheckinResult.WRONG_SESSION,
                    "Vé không thuộc tổ chức của nhân viên");
            return reject(CheckinResult.WRONG_SESSION, "Vé không thuộc sự kiện của bạn");
        }

        CheckinResult verdict = ticket.evaluate(cmd.eventSessionId());
        if (!verdict.accepted()) {
            log.record(ticket.id(), cmd.eventSessionId(), cmd.staffId(), cmd.deviceId(), verdict, null);
            return new Result(
                    verdict.name(),
                    ticket.seatCode(),
                    ticket.seatLabel(),
                    ticket.ticketTypeName(),
                    noteFor(ticket, verdict));
        }

        // Thứ THỰC SỰ quyết định ai được vào.
        boolean won = tickets.checkIn(ticket.id(), cmd.staffId(), clock.instant());
        CheckinResult finalResult = won ? CheckinResult.ACCEPTED : CheckinResult.ALREADY_CHECKED_IN;
        log.record(ticket.id(), cmd.eventSessionId(), cmd.staffId(), cmd.deviceId(), finalResult, null);

        return new Result(
                finalResult.name(),
                ticket.seatCode(),
                ticket.seatLabel(),
                ticket.ticketTypeName(),
                won ? null : "Vé đã được soát trước đó");
    }

    private static String noteFor(Ticket ticket, CheckinResult verdict) {
        return verdict == CheckinResult.ALREADY_CHECKED_IN && ticket.checkedInAt() != null
                ? "Đã soát lúc " + ticket.checkedInAt()
                : null;
    }

    private static Result reject(CheckinResult result, String note) {
        return new Result(result.name(), null, null, null, note);
    }
}
