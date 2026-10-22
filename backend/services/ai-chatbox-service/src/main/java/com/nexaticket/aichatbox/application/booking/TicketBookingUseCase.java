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
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <h2>Bước hai nói "không" thì nhả bước một — "không biết" thì KHÔNG</h2>
 *
 * <p>Ordering từ chối (mã khuyến mãi sai, chỗ hết hạn) thì chỗ vừa giữ bị treo tới 10 phút trong
 * khi khách — vừa được báo "không đặt được" — thử lại và nhận "khu này hết chỗ" do chính chỗ mình
 * vừa giữ. Nhả ngay, theo kiểu cố gắng hết sức: nhả không được thì chỗ vẫn tự hết hạn.
 *
 * <p>Ordering <b>quá hạn</b> thì khác hẳn: saga đặt đơn có thể vẫn đang chạy và commit sau khi
 * chat bỏ cuộc. Bản trước gom hai trường hợp làm một — nhả chỗ, báo lỗi, mô hình gọi đặt vé lại
 * với khoá mới, và khách có hai đơn (R-25). Giờ hỏi lại đúng đơn đó bằng CÙNG khoá một lần: ordering
 * phát lại phản hồi đã lưu (starter-idempotency), trả lại đơn cũ theo {@code hold_id}, và unique
 * index {@code orders.hold_id} chặn đơn thứ hai. Vẫn không biết thì giữ nguyên chỗ và bảo mô hình
 * đừng đặt lại.
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

    /**
     * Ordering không trả lời được cả hai lần: đơn có thể đã được tạo. Mô hình đọc câu này và nói lại
     * — điều quan trọng nhất là nó KHÔNG gọi đặt vé lần nữa.
     */
    public static final String UNCONFIRMED_REASON = "Hệ thống đơn hàng phản hồi chậm nên chưa xác nhận được đơn đã "
            + "được tạo hay chưa. Đừng gọi đặt vé lại — làm vậy có thể tạo đơn thứ hai. Mời khách chờ khoảng một "
            + "phút rồi hỏi \"đơn của tôi\" (gọi getCustomerProfileAndHistory) hoặc xem trang Đơn hàng.";

    /** Chờ trước lần hỏi lại — đủ để một saga chậm kịp commit. */
    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(1);

    private final CatalogClientPort catalog;
    private final InventoryClientPort inventory;
    private final OrderingClientPort ordering;
    private final CallerCredentialsPort credentials;
    private final Duration retryDelay;

    @Autowired
    public TicketBookingUseCase(
            CatalogClientPort catalog,
            InventoryClientPort inventory,
            OrderingClientPort ordering,
            CallerCredentialsPort credentials) {
        this(catalog, inventory, ordering, credentials, DEFAULT_RETRY_DELAY);
    }

    /** Cho test: {@code Duration.ZERO} để không ngủ. */
    public TicketBookingUseCase(
            CatalogClientPort catalog,
            InventoryClientPort inventory,
            OrderingClientPort ordering,
            CallerCredentialsPort credentials,
            Duration retryDelay) {
        this.catalog = catalog;
        this.inventory = inventory;
        this.ordering = ordering;
        this.credentials = credentials;
        this.retryDelay = retryDelay;
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
        boolean sessionBelongs = event.sessions().stream().anyMatch(session -> eventSessionId.equals(session.id()));
        if (!sessionBelongs) {
            return new BookingOutcome.Rejected("Suất diễn này không thuộc sự kiện " + event.title()
                    + ". Lấy lại sessionId từ getEventDetails của đúng sự kiện.");
        }
        // Mã khu CHUẨN của catalog, không phải chuỗi mô hình viết: so khớp ở đây không phân biệt hoa
        // thường, nhưng inventory thì chưa chắc (R-26).
        Optional<String> canonical = event.sessions().stream()
                .filter(session -> eventSessionId.equals(session.id()))
                .flatMap(session -> session.tiers().stream())
                .map(tier -> tier.zoneCode())
                .filter(zoneCode::equalsIgnoreCase)
                .findFirst();
        if (canonical.isEmpty()) {
            return new BookingOutcome.Rejected("Suất này không có khu " + zoneCode
                    + ". Chọn zoneCode trong danh sách hạng vé mà getEventDetails trả về.");
        }
        String zone = canonical.get();

        Optional<ZoneAdmission> admission = catalog.findZoneAdmission(slug, zone);
        if (admission.isEmpty()) {
            return new BookingOutcome.Rejected(
                    "Sơ đồ sự kiện không có khu " + zone + " — mời khách chọn chỗ trên trang sự kiện.");
        }

        String token = credentials.currentAccessToken();
        TicketHold hold;
        try {
            hold = inventory.holdZone(
                    eventSessionId, zone, quantity, admission.get(), token, "chat-hold-" + idempotencySeed);
        } catch (BookingRejectedException e) {
            return new BookingOutcome.Rejected(e.reason());
        }

        return placeWithOneRetry(event, hold, zone, token, idempotencySeed);
    }

    /**
     * Đặt đơn; quá hạn thì hỏi lại đúng một lần bằng CÙNG khoá.
     *
     * <table>
     *   <caption>Kết cục</caption>
     *   <tr><th>Lần đầu</th><th>Lần hỏi lại</th><th>Kết quả</th><th>Chỗ giữ</th></tr>
     *   <tr><td>có đơn</td><td>—</td><td>Started</td><td>giữ</td></tr>
     *   <tr><td>từ chối</td><td>—</td><td>Rejected(lý do)</td><td>nhả</td></tr>
     *   <tr><td>quá hạn</td><td>có đơn</td><td>Started</td><td>giữ</td></tr>
     *   <tr><td>quá hạn</td><td>từ chối</td><td>Rejected(lý do)</td><td>nhả</td></tr>
     *   <tr><td>quá hạn</td><td>vẫn không biết</td><td>Rejected(UNCONFIRMED_REASON)</td><td>GIỮ</td></tr>
     * </table>
     */
    private BookingOutcome placeWithOneRetry(
            EventDetail event, TicketHold hold, String zone, String token, String idempotencySeed) {
        String key = "chat-order-" + idempotencySeed;
        try {
            return started(event, hold, zone, ordering.placeOrder(hold.holdId(), token, key));
        } catch (BookingRejectedException e) {
            releaseQuietly(hold, token);
            return new BookingOutcome.Rejected(e.reason());
        } catch (RemoteCallException e) {
            log.info("Ordering quá hạn khi đặt đơn cho chỗ giữ {} — hỏi lại cùng khoá", hold.holdId());
        }

        try {
            Thread.sleep(retryDelay);
            return started(event, hold, zone, ordering.placeOrder(hold.holdId(), token, key));
        } catch (BookingRejectedException e) {
            releaseQuietly(hold, token);
            return new BookingOutcome.Rejected(e.reason());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unconfirmed(hold, idempotencySeed, e);
        } catch (RuntimeException e) {
            // RemoteCallException hay bất cứ thứ gì khác: chưa biết đơn đã tạo chưa — KHÔNG nhả.
            return unconfirmed(hold, idempotencySeed, e);
        }
    }

    private BookingOutcome started(EventDetail event, TicketHold hold, String zone, PlacedOrder order) {
        log.info(
                "Agent đặt đơn {} ({} vé khu {} của {}) qua chat",
                order.orderNumber(),
                hold.quantity(),
                zone,
                event.title());
        return new BookingOutcome.Started(event, hold, order);
    }

    private BookingOutcome unconfirmed(TicketHold hold, String idempotencySeed, Exception cause) {
        log.warn(
                "Chưa xác nhận được đơn của chỗ giữ {} (seed {}) sau khi hỏi lại — giữ nguyên chỗ: {}",
                hold.holdId(),
                idempotencySeed,
                cause.toString());
        return new BookingOutcome.Rejected(UNCONFIRMED_REASON);
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
