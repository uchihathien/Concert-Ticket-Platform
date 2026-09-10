// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.command;

import com.nexaticket.catalog.application.CatalogAccess;
import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.domain.model.AdmissionKind;
import com.nexaticket.catalog.domain.model.Venue;
import com.nexaticket.catalog.domain.model.VenueZone;
import com.nexaticket.catalog.domain.port.EventRepository;
import com.nexaticket.catalog.domain.port.VenueRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sơ đồ tự dựng của ban tổ chức: khai cả tập khu trong một lần.
 *
 * <p>Đây là đường dành cho tổ chức <b>không</b> dùng khung của nền tảng — ví dụ ba khu VIP / Thường
 * / Khác với số ghế tự chọn. Khác {@code AddZoneHandler} ở chỗ nhận cả tập: đặt "VIP 100 ghế,
 * Thường 500 ghế" là một quyết định duy nhất, và chia nó thành ba lời gọi nghĩa là ba trạng thái
 * trung gian mà một lần mất mạng sẽ để lại vĩnh viễn.
 *
 * <h3>Hai cửa chặn, và vì sao chúng khác nhau</h3>
 *
 * <ul>
 *   <li><b>Địa điểm dựng từ khung</b> ⇒ từ chối. Khu vực trong khung là kết cấu cố định do nền
 *       tảng định nghĩa; muốn sơ đồ riêng thì dựng địa điểm riêng, và đó là thao tác một phút.
 *   <li><b>Địa điểm đã có sự kiện từng lên bán</b> ⇒ từ chối. Tồn kho ở inventory-service được
 *       dựng một lần lúc publish, mang theo đúng những mã khu này. Đổi sơ đồ ở đây không đổi được
 *       những chỗ đã sinh ra bên kia — kết quả là ghế mồ côi vẫn bán được, thuộc một khu không còn
 *       tồn tại. Cách làm đúng vẫn là: rút xuống, sửa, publish lại.
 * </ul>
 */
@Service
public class ConfigureVenueZonesHandler {

    private final VenueRepository venues;
    private final EventRepository events;
    private final CatalogAccess access;

    public ConfigureVenueZonesHandler(VenueRepository venues, EventRepository events, CatalogAccess access) {
        this.venues = venues;
        this.events = events;
        this.access = access;
    }

    /**
     * @param zones tập khu mới, thay hoàn toàn tập cũ. Khu giữ nguyên {@code zoneCode} thì giữ
     *     nguyên id — nên hạng vé đã khai cho nó không mất khi chỉ sửa số ghế.
     */
    @Transactional
    public Result handle(UUID organizationId, UUID venueId, List<ZoneSpec> zones) {
        access.requireCatalogManager(organizationId);

        Venue venue = venues.findById(organizationId, venueId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.VENUE_NOT_FOUND, "Venue not found"));

        if (venue.isFromTemplate()) {
            throw new ApiException(
                    CatalogErrorCode.VENUE_LAYOUT_LOCKED,
                    "Sơ đồ này đến từ khung của nền tảng; hãy tạo địa điểm riêng nếu cần sơ đồ khác");
        }
        if (events.hasNonDraftEventAtVenue(venueId)) {
            throw new ApiException(
                    CatalogErrorCode.VENUE_IN_USE, "Địa điểm đã có sự kiện từng lên bán; rút sự kiện xuống trước");
        }
        if (zones.isEmpty()) {
            // Xoá sạch khu là dựng một địa điểm không bán được gì. Chặn ở đây thay vì để nó lộ ra
            // dưới dạng VENUE_WITHOUT_ZONE ở bước publish, cách chỗ gây lỗi vài màn hình.
            throw new ApiException(CatalogErrorCode.ZONE_NOT_FOUND, "Sơ đồ phải có ít nhất một khu");
        }

        Set<String> seen = new LinkedHashSet<>();
        List<VenueZone> replacement = new ArrayList<>(zones.size());
        for (int i = 0; i < zones.size(); i++) {
            ZoneSpec spec = zones.get(i);
            if (!seen.add(spec.zoneCode())) {
                throw new ApiException(CatalogErrorCode.ZONE_ALREADY_PRICED, "Mã khu trùng nhau: " + spec.zoneCode());
            }
            replacement.add(new VenueZone(
                    // Id mới ở đây chỉ là id đề xuất: repository upsert theo (venue_id, zone_code)
                    // nên khu đã có giữ nguyên id cũ của nó, và hạng vé trỏ vào không bị đứt.
                    UUID.randomUUID(),
                    venueId,
                    spec.zoneCode(),
                    spec.name(),
                    parseKind(spec.kind()),
                    spec.rowCount(),
                    spec.seatsPerRow(),
                    spec.capacity(),
                    spec.sortOrder() == null ? i : spec.sortOrder()));
        }

        VenueRepository.ZoneReplacement applied = venues.replaceZones(venueId, replacement);
        return new Result(applied.inserted(), applied.updated(), applied.removedZones(), applied.removedTicketTypes());
    }

    /**
     * Kết quả ở tầng application.
     *
     * <p>Dựng lại thay vì trả thẳng {@code VenueRepository.ZoneReplacement}: kiểu kia thuộc domain,
     * và tầng interfaces không được chạm vào domain (ArchitectureRules.hexagonalLayers).
     *
     * @param removedTicketTypes số hạng vé bị xoá theo khu không còn nữa — luôn thuộc sự kiện còn
     *     nháp, vì lệnh này từ chối chạy khi địa điểm đã có sự kiện từng lên bán
     */
    public record Result(int inserted, int updated, int removedZones, int removedTicketTypes) {}

    /** Hình dạng đầu vào của một khu; {@code kind} là chuỗi vì tầng interfaces không chạm domain. */
    public record ZoneSpec(
            String zoneCode,
            String name,
            String kind,
            Integer rowCount,
            Integer seatsPerRow,
            Integer capacity,
            Integer sortOrder) {}

    private static AdmissionKind parseKind(String kind) {
        try {
            return AdmissionKind.valueOf(kind);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(CatalogErrorCode.ZONE_NOT_FOUND, "Loại khu không hợp lệ: " + kind);
        }
    }
}
