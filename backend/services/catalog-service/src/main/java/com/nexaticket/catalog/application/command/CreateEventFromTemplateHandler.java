// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.command;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.application.SlugAllocator;
import com.nexaticket.catalog.domain.model.ConcertTemplate;
import com.nexaticket.catalog.domain.model.Event;
import com.nexaticket.catalog.domain.model.EventSession;
import com.nexaticket.catalog.domain.model.TemplateZone;
import com.nexaticket.catalog.domain.model.TicketType;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.model.VenueZone;
import com.nexaticket.catalog.domain.port.ConcertTemplateRepository;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.catalog.domain.port.VenueRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dựng trọn một sự kiện từ khung của Tổng công ty.
 *
 * <p>Đây là lý do khung tồn tại: một lời gọi thay cho bốn màn hình (địa điểm → từng khu → sự kiện →
 * suất diễn → từng hạng vé). Tổ chức chỉ điền phần <b>của họ</b> — tên sự kiện, giờ diễn, giá — còn
 * kết cấu khán phòng thì chép từ khung.
 *
 * <p><b>Một transaction cho tất cả.</b> Hỏng giữa chừng mà vẫn commit phần đã làm sẽ để lại một địa
 * điểm mồ côi mang mã khu của khung, và lần thử lại sẽ tạo thêm một địa điểm nữa — sau ba lần bấm
 * là ba địa điểm trùng tên trong danh sách của ban tổ chức.
 *
 * <p><b>Chép chứ không tham chiếu.</b> Sau lời gọi này, sự kiện không còn phụ thuộc vào khung: nền
 * tảng sửa hay lưu trữ khung thì sơ đồ đã bán vẫn nguyên. Cùng nguyên tắc snapshot mà hệ thống áp
 * cho giá và cho tài khoản ngân hàng.
 */
@Service
public class CreateEventFromTemplateHandler {

    private final ConcertTemplateRepository templates;
    private final VenueRepository venues;
    private final EventRepository events;
    private final CatalogAccess access;
    private final SlugAllocator slugs;

    public CreateEventFromTemplateHandler(
            ConcertTemplateRepository templates,
            VenueRepository venues,
            EventRepository events,
            CatalogAccess access,
            SlugAllocator slugs) {
        this.templates = templates;
        this.venues = venues;
        this.events = events;
        this.access = access;
        this.slugs = slugs;
    }

    /**
     * @param zonePrices giá theo mã khu; khu không có mặt ở đây thì lấy giá gợi ý của khung
     * @return id của sự kiện vừa dựng, vẫn ở trạng thái nháp — publish là một quyết định riêng
     */
    @Transactional
    public UUID handle(UUID organizationId, Command command) {
        access.requireCatalogManager(organizationId);

        ConcertTemplate template = templates
                .findById(command.templateId())
                .orElseThrow(() -> new ApiException(CatalogErrorCode.TEMPLATE_NOT_FOUND, "Template not found"));

        if (!template.status().isUsable()) {
            throw new ApiException(
                    CatalogErrorCode.TEMPLATE_NOT_USABLE, "Khung đang ở trạng thái " + template.status());
        }
        if (template.zones().isEmpty()) {
            // Không xảy ra với khung ACTIVE — activate() đã chặn. Vẫn kiểm ở đây vì nhánh còn lại
            // là dựng một sự kiện không bán được gì, và lỗi đó chỉ lộ ra ở bước publish.
            throw new ApiException(CatalogErrorCode.TEMPLATE_WITHOUT_ZONE, "Khung chưa khai khu nào");
        }

        // Giá phải giải xong TRƯỚC khi ghi bất cứ thứ gì: thiếu giá là lỗi của request, và người
        // dùng cần thấy nó thay vì thấy một sự kiện dựng dở.
        Map<String, Long> prices = resolvePrices(template, command.zonePrices());

        Venue venue = Venue.fromTemplate(
                organizationId, command.venueName(), command.city(), command.address(), template.id());
        List<VenueZone> zones = template.zones().stream()
                .map(zone -> zone.materialize(venue.id()))
                .toList();
        venues.saveWithZones(venue, zones);

        Event event = Event.draft(
                organizationId,
                venue.id(),
                slugs.allocate(command.slug(), command.title()),
                command.title(),
                command.summary(),
                command.description(),
                command.category() == null || command.category().isBlank() ? template.category() : command.category(),
                command.posterUrl());
        events.insert(event);

        EventSession session = EventSession.create(
                event.id(),
                command.startsAt(),
                command.endsAt(),
                command.salesOpenAt(),
                command.salesCloseAt(),
                command.maxSeatedPerHold(),
                command.maxStandingPerHold(),
                command.maxUnitsPerHold(),
                command.maxTicketsPerCustomer());
        events.addSession(session);

        for (VenueZone zone : zones) {
            events.addTicketType(TicketType.create(
                    session.id(), zone.id(), zone.name(), prices.get(zone.zoneCode()), zone.sortOrder()));
        }
        return event.id();
    }

    /**
     * Giá của từng khu: request thắng, rồi tới gợi ý của khung, không có cả hai thì từ chối.
     *
     * <p>Không rơi về 0. Giá 0 là "vé mời" — một quyết định thương mại có thật — nên lấy nó làm giá
     * mặc định khi thiếu thông tin sẽ biến một ô nhập bị bỏ quên thành một khu bán miễn phí.
     */
    private static Map<String, Long> resolvePrices(ConcertTemplate template, Map<String, Long> requested) {
        Map<String, Long> resolved = new HashMap<>();
        List<String> missing = new ArrayList<>();

        for (TemplateZone zone : template.zones()) {
            Long price = requested.get(zone.zoneCode());
            if (price == null) {
                price = zone.suggestedPriceVnd();
            }
            if (price == null) {
                missing.add(zone.zoneCode());
            } else if (price < 0) {
                throw new ApiException(
                        CatalogErrorCode.ZONE_PRICE_REQUIRED, "Giá vé không được âm: " + zone.zoneCode());
            } else {
                resolved.put(zone.zoneCode(), price);
            }
        }

        if (!missing.isEmpty()) {
            throw new ApiException(
                    CatalogErrorCode.ZONE_PRICE_REQUIRED,
                    "Khung không gợi ý giá cho các khu này, hãy khai giá",
                    Map.of("zoneCodes", missing));
        }
        return resolved;
    }

    /**
     * Hình dạng lệnh ở tầng application.
     *
     * <p>Gộp vào một record thay vì mười lăm tham số: một danh sách tham số dài toàn {@code String}
     * và {@code Instant} là một chỗ để hoán đổi nhầm hai giá trị mà trình biên dịch không nói gì.
     */
    public record Command(
            UUID templateId,
            String title,
            String slug,
            String summary,
            String description,
            String category,
            String posterUrl,
            String venueName,
            String city,
            String address,
            Instant startsAt,
            Instant endsAt,
            Instant salesOpenAt,
            Instant salesCloseAt,
            Integer maxSeatedPerHold,
            Integer maxStandingPerHold,
            Integer maxUnitsPerHold,
            Integer maxTicketsPerCustomer,
            Map<String, Long> zonePrices) {

        public Command {
            zonePrices = zonePrices == null ? Map.of() : Map.copyOf(zonePrices);
        }
    }
}
