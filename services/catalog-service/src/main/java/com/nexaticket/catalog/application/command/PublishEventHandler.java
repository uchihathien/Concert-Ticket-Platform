// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.command;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.CatalogProperties;
import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.model.EventSession;
import com.nexaticket.catalog.domain.model.PublishBlocker;
import com.nexaticket.catalog.domain.model.PurchaseLimits;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.catalog.domain.port.VenueRepository;
import com.nexaticket.platform.outbox.OutboxWriter;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đưa sự kiện lên bán.
 *
 * <p>Đây là hành động quan trọng nhất của service này: nó là chỗ duy nhất tồn kho được sinh ra.
 * Ba tính chất phải giữ.
 *
 * <ol>
 *   <li><b>Đổi trạng thái và phát sự kiện nằm trong cùng một transaction</b>, qua outbox
 *       (ADR-0005). Publish lên broker trước khi commit thì một lần rollback sẽ để lại tồn kho cho
 *       một sự kiện mà database nói là vẫn còn nháp.
 *   <li><b>Mỗi suất diễn một message.</b> Inventory dựng tồn kho theo suất, và một sự kiện mười
 *       suất có thể nặng hàng megabyte nếu gộp. Tách ra cũng để một suất hỏng không kéo chín suất
 *       kia hỏng theo.
 *   <li><b>Kiểm tra hết rồi mới đổi gì.</b> {@code preflight} chạy trước, trả về cả danh sách để
 *       màn hình publish hiện đủ checklist.
 * </ol>
 *
 * <p>Publish lại một sự kiện đã rút xuống là an toàn: Inventory nhận message với cùng
 * {@code eventSessionId} và {@code ON CONFLICT DO NOTHING} giữ nguyên tồn kho cũ, nên ghế đã bán
 * không bị dựng lại thành trống.
 */
@Service
public class PublishEventHandler {

    static final String EXCHANGE = "nexaticket.catalog";
    static final String EVENT_TYPE = "session.published";

    private final EventRepository events;
    private final VenueRepository venues;
    private final OutboxWriter outbox;
    private final CatalogAccess access;
    private final CatalogProperties properties;
    private final Clock clock;

    public PublishEventHandler(
            EventRepository events,
            VenueRepository venues,
            OutboxWriter outbox,
            CatalogAccess access,
            CatalogProperties properties,
            Clock clock) {
        this.events = events;
        this.venues = venues;
        this.outbox = outbox;
        this.access = access;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public void handle(UUID organizationId, UUID eventId) {
        access.requireCatalogManager(organizationId);

        Event event = events.findByIdForOrganization(organizationId, eventId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.EVENT_NOT_FOUND, "Event not found"));
        Venue venue = venues.findById(organizationId, event.venueId())
                .orElseThrow(() -> new ApiException(CatalogErrorCode.VENUE_NOT_FOUND, "Venue not found"));

        apply(event, venue);
    }

    /**
     * Phát lại {@code session.published} cho một sự kiện <b>đang bán</b>, không đổi trạng thái gì.
     *
     * <h2>Vì sao cần một đường riêng thay vì bảo họ rút xuống rồi bán lại</h2>
     *
     * <p>Toạ độ chỗ ngồi nằm ở Inventory và được chốt lúc publish lần đầu. Những suất publish
     * trước khi Catalog biết tính hình học đã chốt chỉ số hàng/cột thay vì toạ độ mét, nên sơ đồ
     * của chúng vẽ sai và giao diện phải từ chối vẽ. Đường chữa là gửi lại payload — Inventory
     * đồng bộ toạ độ mà không đụng tới giá, trạng thái hay người đang giữ chỗ.
     *
     * <p>Rút xuống rồi bán lại cũng phát lại message, nhưng nó <b>gỡ sự kiện khỏi trang khách</b>
     * trong khoảng giữa và huỷ luôn những link đang được chia sẻ. Đổi một tấm sơ đồ không đáng
     * giá đó.
     *
     * @return số suất đã phát lại
     */
    @Transactional
    public int resyncInventory(UUID organizationId, UUID eventId) {
        access.requireCatalogManager(organizationId);

        Event event = events.findByIdForOrganization(organizationId, eventId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.EVENT_NOT_FOUND, "Event not found"));
        Venue venue = venues.findById(organizationId, event.venueId())
                .orElseThrow(() -> new ApiException(CatalogErrorCode.VENUE_NOT_FOUND, "Venue not found"));

        if (event.status() != com.nexaticket.catalog.domain.model.EventStatus.PUBLISHED) {
            // Sự kiện chưa bán thì chưa có tồn kho nào để đồng bộ, và gửi message cho nó sẽ DỰNG
            // tồn kho — tức là publish lén qua một cửa không kiểm preflight.
            throw new ApiException(CatalogErrorCode.INVALID_EVENT_STATE, "Chỉ đồng bộ được sự kiện đang bán");
        }

        return emitSessions(event, venue);
    }

    /**
     * Cơ chế publish, KHÔNG kiểm quyền.
     *
     * <p>Tách ra để bộ dựng dữ liệu mẫu dùng lại. Nó chạy lúc khởi động, không có người dùng nào
     * và cũng không có {@code TenantScope} nào để hỏi — nhưng nó vẫn phải sinh ra đúng thứ payload
     * mà API sinh ra, nếu không dữ liệu mẫu sẽ khác dữ liệu thật ở đúng chỗ khó phát hiện nhất.
     *
     * <p>Chỉ gọi từ chỗ đã tự chịu trách nhiệm về quyền.
     */
    @Transactional
    public Event apply(Event event, Venue venue) {
        if (!event.status().canPublish()) {
            throw new ApiException(
                    CatalogErrorCode.INVALID_EVENT_STATE, "Không publish được sự kiện ở trạng thái " + event.status());
        }

        List<PublishBlocker> blockers = event.preflight(venue);
        if (!blockers.isEmpty()) {
            throw new ApiException(
                    CatalogErrorCode.PUBLISH_BLOCKED,
                    "Sự kiện chưa sẵn sàng để bán",
                    Map.of("blockers", blockers.stream().map(Enum::name).toList()));
        }

        event.publish(clock.instant());
        events.update(event);

        emitSessions(event, venue);
        return event;
    }

    /** Một message cho mỗi suất — xem tính chất 2 ở đầu lớp. */
    private int emitSessions(Event event, Venue venue) {
        PurchaseLimits cap = properties.platformCap();
        for (EventSession session : event.sessions()) {
            PurchaseLimits limits = PurchaseLimits.resolve(
                    session.maxSeatedPerHold(),
                    session.maxStandingPerHold(),
                    session.maxUnitsPerHold(),
                    session.maxTicketsPerCustomer(),
                    cap);
            outbox.append(
                    EXCHANGE,
                    "EventSession",
                    session.id(),
                    EVENT_TYPE,
                    SessionPublishedPayload.of(event, session, venue, limits));
        }
        return event.sessions().size();
    }
}
