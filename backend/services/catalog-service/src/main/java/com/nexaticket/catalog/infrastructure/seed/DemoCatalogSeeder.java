// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.seed;

import com.nexaticket.catalog.application.CatalogProperties;
import com.nexaticket.catalog.application.command.PublishEventHandler;
import com.nexaticket.catalog.domain.model.AdmissionKind;
import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.model.EventSession;
import com.nexaticket.catalog.domain.model.Slug;
import com.nexaticket.catalog.domain.model.TicketType;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.model.VenueZone;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.catalog.domain.port.VenueRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dựng một catalog mẫu lúc khởi động.
 *
 * <p>Không phải để trang trí: một catalog rỗng khiến cả hệ thống trông như hỏng. Trang chủ trắng,
 * bộ lọc không có gì để lọc, và luồng mua vé — thứ quan trọng nhất — không có suất nào để thử.
 * Ai vừa clone repo về sẽ không phân biệt được "chưa có dữ liệu" với "code hỏng".
 *
 * <p><b>Idempotent theo slug.</b> Mỗi sự kiện có UUID suy ra từ tên nên khởi động lại không sinh
 * bản sao; sự kiện nào đã có thì bỏ qua nguyên cụm. Nhờ vậy dữ liệu bạn tự sửa trên giao diện
 * không bị ghi đè ở lần khởi động sau.
 *
 * <p><b>Ngày giờ tính theo thời điểm chạy</b>, không phải hằng số. Ghi cứng "tháng 10 năm 2026" thì
 * đến năm 2027 mọi sự kiện mẫu đều thành quá khứ, bộ lọc "sắp diễn ra" trống trơn, và người ta lại
 * tưởng code hỏng — đúng cái bẫy mà bộ dựng này sinh ra để tránh.
 *
 * <p>Đi thẳng qua repository chứ không qua handler vì handler đòi {@code TenantScope}, mà lúc khởi
 * động thì không có người dùng nào. Riêng bước publish thì gọi lại
 * {@link PublishEventHandler#apply} để payload gửi sang Inventory giống hệt payload của API thật.
 */
@Component
@ConditionalOnProperty(prefix = "nexaticket.catalog", name = "demo-data", havingValue = "true")
public class DemoCatalogSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoCatalogSeeder.class);
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private final VenueRepository venues;
    private final EventRepository events;
    private final PublishEventHandler publish;
    private final CatalogProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public DemoCatalogSeeder(
            VenueRepository venues,
            EventRepository events,
            PublishEventHandler publish,
            CatalogProperties properties,
            TransactionTemplate transactions,
            Clock clock) {
        this.venues = venues;
        this.events = events;
        this.publish = publish;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID org = properties.demoOrganizationId();
        int created = 0;

        for (VenueSpec spec : DemoData.VENUES) {
            if (ensureVenue(org, spec)) {
                created++;
            }
        }
        for (EventSpec spec : DemoData.EVENTS) {
            if (ensureEvent(org, spec)) {
                created++;
            }
        }

        if (created > 0) {
            log.info("Đã dựng {} mục dữ liệu mẫu cho tổ chức {}", created, org);
        }
    }

    /** @return true nếu vừa tạo mới */
    private boolean ensureVenue(UUID organizationId, VenueSpec spec) {
        UUID venueId = idOf("venue", spec.key());
        if (venues.findById(organizationId, venueId).isPresent()) {
            return false;
        }
        return Boolean.TRUE.equals(transactions.execute(status -> {
            venues.save(new Venue(venueId, organizationId, spec.name(), spec.city(), spec.address(), List.of()));
            int order = 0;
            for (ZoneSpec zone : spec.zones()) {
                venues.addZone(new VenueZone(
                        idOf("zone", spec.key() + ":" + zone.code()),
                        venueId,
                        zone.code(),
                        zone.name(),
                        zone.kind(),
                        zone.rowCount(),
                        zone.seatsPerRow(),
                        zone.capacity(),
                        order++));
            }
            return true;
        }));
    }

    private boolean ensureEvent(UUID organizationId, EventSpec spec) {
        Slug slug = new Slug(spec.slug());
        if (events.slugExists(slug)) {
            return false;
        }

        UUID venueId = idOf("venue", spec.venueKey());
        Venue venue = venues.findById(organizationId, venueId).orElse(null);
        if (venue == null) {
            log.warn("Bỏ qua sự kiện mẫu {}: chưa có địa điểm {}", spec.slug(), spec.venueKey());
            return false;
        }

        return Boolean.TRUE.equals(transactions.execute(status -> {
            Event event = new Event(
                    idOf("event", spec.slug()),
                    organizationId,
                    venueId,
                    slug,
                    spec.title(),
                    spec.summary(),
                    spec.description(),
                    spec.category(),
                    null,
                    com.nexaticket.catalog.domain.model.EventStatus.DRAFT,
                    null,
                    List.of());
            events.insert(event);

            for (SessionSpec session : spec.sessions()) {
                EventSession created = session.toDomain(event.id(), clock.instant());
                events.addSession(created);

                int order = 0;
                for (PriceSpec price : session.prices()) {
                    UUID zoneId = idOf("zone", spec.venueKey() + ":" + price.zoneCode());
                    TicketType type = new TicketType(
                            idOf("type", created.id() + ":" + price.zoneCode()),
                            created.id(),
                            zoneId,
                            price.name(),
                            price.priceVnd(),
                            order++);
                    events.addTicketType(type);
                }
            }

            // Đọc lại để lấy aggregate đầy đủ: publish cần cả suất diễn lẫn hạng vé để dựng
            // payload, mà những thứ đó vừa được ghi bằng câu INSERT riêng chứ không nằm trong
            // đối tượng `event` ở trên.
            Event complete =
                    events.findByIdForOrganization(organizationId, event.id()).orElseThrow();
            if (spec.publish()) {
                publish.apply(complete, venue);
            }
            return true;
        }));
    }

    /**
     * UUID suy ra từ tên, không phải ngẫu nhiên.
     *
     * <p>Nhờ vậy chạy lại bộ dựng trên một database trống cho ra đúng các id cũ — link đã lưu, ảnh
     * chụp màn hình trong tài liệu, và dữ liệu thử ở service khác đều còn khớp. Tiền tố
     * {@code nexaticket:demo:} để id mẫu không bao giờ đụng id thật.
     */
    private static UUID idOf(String kind, String key) {
        return UUID.nameUUIDFromBytes(("nexaticket:demo:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    // --- Hình dạng dữ liệu mẫu ---------------------------------------------

    record ZoneSpec(
            String code, String name, AdmissionKind kind, Integer rowCount, Integer seatsPerRow, Integer capacity) {

        static ZoneSpec seated(String code, String name, int rows, int seatsPerRow) {
            return new ZoneSpec(code, name, AdmissionKind.SEATED, rows, seatsPerRow, null);
        }

        static ZoneSpec standing(String code, String name, int capacity) {
            return new ZoneSpec(code, name, AdmissionKind.STANDING, null, null, capacity);
        }
    }

    record VenueSpec(String key, String name, String city, String address, List<ZoneSpec> zones) {}

    record PriceSpec(String zoneCode, String name, long priceVnd) {}

    /**
     * Một suất diễn, mô tả bằng "cách hôm nay bao nhiêu ngày".
     *
     * @param daysFromNow số ngày kể từ lúc chạy; âm thì là suất đã diễn qua
     * @param hourVn giờ diễn theo giờ Việt Nam
     * @param salesOpenDaysBefore cửa bán mở trước giờ diễn bao nhiêu ngày
     */
    record SessionSpec(
            int daysFromNow, int hourVn, int durationMinutes, int salesOpenDaysBefore, List<PriceSpec> prices) {

        EventSession toDomain(UUID eventId, Instant now) {
            ZonedDateTime start =
                    ZonedDateTime.ofInstant(now, VN).plusDays(daysFromNow).with(LocalTime.of(hourVn, 0));
            Instant startsAt = start.toInstant();
            return EventSession.create(
                    eventId,
                    startsAt,
                    startsAt.plus(Duration.ofMinutes(durationMinutes)),
                    // Cửa bán mở trong quá khứ để sự kiện mẫu mua được ngay, không phải chờ.
                    startsAt.minus(Duration.ofDays(salesOpenDaysBefore)),
                    // Đóng cửa bán lúc suất bắt đầu: đủ đơn giản cho dữ liệu mẫu.
                    startsAt,
                    null,
                    null,
                    null,
                    null);
        }
    }

    record EventSpec(
            String slug,
            String title,
            String summary,
            String description,
            String category,
            String venueKey,
            boolean publish,
            List<SessionSpec> sessions) {}
}
