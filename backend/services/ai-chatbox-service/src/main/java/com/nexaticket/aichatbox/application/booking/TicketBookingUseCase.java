// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.booking;

import com.nexaticket.aichatbox.domain.model.EventDetail;
import com.nexaticket.aichatbox.domain.model.PlacedOrder;
import com.nexaticket.aichatbox.domain.model.TicketHold;
import com.nexaticket.aichatbox.domain.model.ZoneAdmission;
import com.nexaticket.aichatbox.domain.port.BookingRejectedException;
import com.nexaticket.aichatbox.domain.port.CallerCredentialsPort;
import com.nexaticket.aichatbox.domain.port.CatalogClientPort;
import com.nexaticket.aichatbox.domain.port.InventoryClientPort;
import com.nexaticket.aichatbox.domain.port.OrderingClientPort;
import com.nexaticket.aichatbox.domain.port.RemoteCallException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Đặt vé hộ khách ngay trong khung chat: giữ chỗ ở inventory, rồi đặt đơn ở ordering.
 *
 * <h2>Hai bước, cùng quy chế với trang web</h2>
 *
 * <p>Agent không có đường tắt nào. Nó gọi đúng hai endpoint mà nút "Đặt vé" trên web gọi, bằng
 * token của chính khách, nên <b>mọi chốt chặn vẫn nguyên</b>: hạn mức mua của một người, số chỗ
 * còn trống, suất đã đóng bán, chỗ giữ 10 phút tự nhả, hạn thanh toán 15 phút của đơn. Những con
 * số ấy không khai lại ở đây — chúng được đọc từ phản hồi của hai service và nói lại cho khách.
 *
 * <h2>Bước hai hỏng thì nhả bước một</h2>
 *
 * <p>Giữ chỗ xong mà đặt đơn không được (ordering quá hạn, mã khuyến mãi sai) thì chỗ ấy bị treo
 * tới 10 phút trong khi khách — vừa được báo "không đặt được" — thử lại và nhận "khu này hết
 * chỗ" do chính chỗ mình vừa giữ. Nhả ngay, và nhả theo kiểu cố gắng hết sức: nhả không được
 * thì chỗ vẫn tự hết hạn, còn lỗi thật của bước đặt đơn thì phải được báo lên.
 *
 * <h2>Khoá chống trùng sinh từ lời gọi tool</h2>
 *
 * <p>Cả hai endpoint bắt buộc {@code Idempotency-Key}. Khoá sinh từ {@code callId} của mô hình —
 * duy nhất cho mỗi lần nó quyết định gọi tool — nên cùng một quyết định gửi lại (mạng chập, lượt
 * chat chạy lại) không giữ thêm chỗ và không đặt thêm đơn.
 */
@Service
public class TicketBookingUseCase {

    private static final Logger log = LoggerFactory.getLogger(TicketBookingUseCase.class);

    /**
     * Trần số vé một lần đặt qua chat.
     *
     * <p>Thấp hơn hạn mức của inventory một cách có chủ đích: khách mua số lượng lớn nên dùng trang
     * web, nơi họ thấy sơ đồ và tổng tiền trước khi bấm. Qua chat, "mười vé" rất có thể là mô hình
     * đọc sai "một vé".
     */
    static final int MAX_QUANTITY = 6;

    private final CatalogClientPort catalog;
    private final InventoryClientPort inventory;
    private final OrderingClientPort ordering;
    private final CallerCredentialsPort credentials;

    public TicketBookingUseCase(
            CatalogClientPort catalog,
            InventoryClientPort inventory,
            OrderingClientPort ordering,
            CallerCredentialsPort credentials) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.ordering = ordering;
        this.credentials = credentials;
    }

    /** Kết quả đặt vé — hoặc đã có đơn chờ thanh toán, hoặc một lý do để nói lại với khách. */
    public sealed interface BookingOutcome {

        record Started(EventDetail event, TicketHold hold, PlacedOrder order) implements BookingOutcome {}

        /** @param reason câu cho mô hình đọc rồi nói lại — là thông tin, không phải sự cố */
        record Rejected(String reason) implements BookingOutcome {}
    }

    /**
     * @param slug sự kiện, từ {@code findEvents}
     * @param eventSessionId suất, từ {@code getEventDetails}
     * @param zoneCode khu, từ {@code getEventDetails}
     * @param idempotencySeed chuỗi duy nhất cho lần gọi này — {@code callId} của tool
     * @throws RemoteCallException một trong ba service không phản hồi; bên gọi biến nó thành kết
     *     quả tool lỗi
     */
    public BookingOutcome initiate(
            String slug, UUID eventSessionId, String zoneCode, int quantity, String idempotencySeed) {

        if (quantity < 1) {
            return new BookingOutcome.Rejected("Số lượng vé phải từ 1 trở lên. Hỏi khách muốn mấy vé.");
        }
        if (quantity > MAX_QUANTITY) {
            return new BookingOutcome.Rejected("Qua chat chỉ đặt được tối đa " + MAX_QUANTITY
                    + " vé một lần. Mời khách đặt trên trang sự kiện nếu cần nhiều hơn.");
        }

        Optional<EventDetail> found = catalog.findEventBySlug(slug);
        if (found.isEmpty()) {
            return new BookingOutcome.Rejected(
                    "Không có sự kiện nào mang slug này. Gọi findEvents để lấy slug đúng, đừng tự dựng.");
        }
        EventDetail event = found.get();
        boolean sessionBelongs =
                event.sessions().stream().anyMatch(session -> eventSessionId.equals(session.id()));
        if (!sessionBelongs) {
            return new BookingOutcome.Rejected("Suất diễn này không thuộc sự kiện " + event.title()
                    + ". Lấy lại sessionId từ getEventDetails của đúng sự kiện.");
        }
        boolean zoneBelongs = event.sessions().stream()
                .filter(session -> eventSessionId.equals(session.id()))
                .flatMap(session -> session.tiers().stream())
                .anyMatch(tier -> zoneCode.equalsIgnoreCase(tier.zoneCode()));
        if (!zoneBelongs) {
            return new BookingOutcome.Rejected("Suất này không có khu " + zoneCode
                    + ". Chọn zoneCode trong danh sách hạng vé mà getEventDetails trả về.");
        }

        Optional<ZoneAdmission> admission = catalog.findZoneAdmission(slug, zoneCode);
        if (admission.isEmpty()) {
            return new BookingOutcome.Rejected(
                    "Sơ đồ sự kiện không có khu " + zoneCode + " — mời khách chọn chỗ trên trang sự kiện.");
        }

        String token = credentials.currentAccessToken();
        TicketHold hold;
        try {
            hold = inventory.holdZone(
                    eventSessionId, zoneCode, quantity, admission.get(), token, "chat-hold-" + idempotencySeed);
        } catch (BookingRejectedException e) {
            return new BookingOutcome.Rejected(e.reason());
        }

        try {
            PlacedOrder order = ordering.placeOrder(hold.holdId(), token, "chat-order-" + idempotencySeed);
            log.info(
                    "Agent đặt đơn {} ({} vé khu {} của {}) qua chat",
                    order.orderNumber(),
                    hold.quantity(),
                    zoneCode,
                    event.title());
            return new BookingOutcome.Started(event, hold, order);
        } catch (BookingRejectedException e) {
            releaseQuietly(hold, token);
            return new BookingOutcome.Rejected(e.reason());
        } catch (RemoteCallException e) {
            releaseQuietly(hold, token);
            throw e;
        }
    }

    private void releaseQuietly(TicketHold hold, String token) {
        try {
            inventory.releaseHold(hold.holdId(), token);
        } catch (RuntimeException e) {
            // Chỗ sẽ tự hết hạn. Lỗi ở đây không được che mất lỗi thật của bước đặt đơn.
            log.warn("Không nhả được chỗ giữ {} sau khi đặt đơn hỏng — nó sẽ tự hết hạn", hold.holdId(), e);
        }
    }
}
