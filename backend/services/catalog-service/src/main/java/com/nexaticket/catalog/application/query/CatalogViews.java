// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.query;

import com.nexaticket.catalog.domain.model.LayoutShape;
import com.nexaticket.catalog.domain.model.StageArea;
import com.nexaticket.catalog.domain.model.ZoneLayout;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO của đường đọc.
 *
 * <p>Gom vào một file vì chúng chỉ có nghĩa khi đọc cùng nhau: đây là hình dạng JSON mà frontend
 * nhận, và một thay đổi ở đây thường kéo theo hai ba record khác. Tách ra mười file làm việc rà
 * lại hợp đồng phải mở mười tab.
 *
 * <p>Đây là DTO của tầng application, không phải aggregate của domain: controller chỉ được chạm
 * vào tầng application (ArchitectureRules.hexagonalLayers), và đường đọc không cần bất biến của
 * aggregate — nó cần đúng những cột mà màn hình hiển thị.
 */
public final class CatalogViews {

    private CatalogViews() {}

    /** Một thẻ sự kiện trên trang danh sách công khai. */
    public record EventCard(
            String slug,
            String title,
            String summary,
            String category,
            String posterUrl,
            String city,
            String venueName,
            Instant nextSessionAt,
            Long fromPriceVnd,
            int sessionCount) {}

    /** Trang chi tiết công khai. */
    public record EventDetail(
            String slug,
            String title,
            String summary,
            String description,
            String category,
            String posterUrl,
            String city,
            String venueName,
            String venueAddress,
            List<PublicSession> sessions) {}

    public record PublicSession(
            UUID id,
            Instant startsAt,
            Instant endsAt,
            Instant salesOpenAt,
            Instant salesCloseAt,
            List<PublicTier> tiers) {

        /** Nhãn "từ 500.000đ". Không có hạng vé nào thì null, và frontend ẩn nhãn. */
        public Long fromPriceVnd() {
            return tiers.stream().mapToLong(PublicTier::priceVnd).min().stream()
                    .boxed()
                    .findFirst()
                    .orElse(null);
        }
    }

    public record PublicTier(UUID id, String name, long priceVnd, String zoneCode, String zoneName, int capacity) {}

    /** Một dòng trong bảng sự kiện của khu vực quản trị. Có cả nháp. */
    public record AdminEventRow(
            UUID id,
            String slug,
            String title,
            String category,
            String status,
            Instant publishedAt,
            String venueName,
            Instant nextSessionAt,
            int sessionCount,
            int ticketTypeCount,
            int capacity) {}

    /** Chi tiết một sự kiện trong khu vực quản trị, đủ để dựng cả trang chỉnh sửa. */
    public record AdminEventDetail(
            UUID id,
            String slug,
            String title,
            String summary,
            String description,
            String category,
            String posterUrl,
            String status,
            Instant publishedAt,
            AdminVenue venue,
            List<AdminSession> sessions,
            /** Những gì còn thiếu để publish. Rỗng nghĩa là bấm Publish được. */
            List<String> blockers) {}

    public record AdminSession(
            UUID id,
            Instant startsAt,
            Instant endsAt,
            Instant salesOpenAt,
            Instant salesCloseAt,
            Integer maxSeatedPerHold,
            Integer maxStandingPerHold,
            Integer maxUnitsPerHold,
            Integer maxTicketsPerCustomer,
            List<AdminTicketType> ticketTypes) {}

    public record AdminTicketType(
            UUID id, UUID venueZoneId, String zoneCode, String zoneName, String name, long priceVnd, int capacity) {}

    /**
     * @param stage {@code null} nghĩa là địa điểm chưa khai sân khấu và dùng sân khấu mặc định.
     *     Cố ý giữ {@code null} thay vì giải sẵn: đây là DTO của màn hình <b>sửa</b>, và nó phải
     *     phân biệt được "chưa khai" với "khai đúng bằng mặc định" — gửi lại bản đã giải sẵn qua
     *     {@code PUT} sẽ biến mọi địa điểm thành đã-khai sau một lần lưu. Bản đã giải nằm ở
     *     {@code GET /venues/{id}/floor-plan}, nơi không ai gửi ngược lại.
     */
    public record AdminVenue(
            UUID id, String name, String city, String address, int capacity, AdminStage stage, List<AdminZone> zones) {}

    public record AdminStage(String shape, double x, double y, double width, double height) {

        /** {@code null} đi thẳng qua: màn hình sửa phải phân biệt "chưa khai" với "khai bằng mặc định". */
        public static AdminStage of(StageArea stage) {
            return stage == null
                    ? null
                    : new AdminStage(
                            stage.shape().name(), stage.x(), stage.y(), stage.width(), stage.effectiveHeight());
        }
    }

    /** @param layout {@code null} nghĩa là khu chưa đặt vị trí và bố cục tự động xếp nó */
    public record AdminZone(
            UUID id,
            String zoneCode,
            String name,
            String kind,
            Integer rowCount,
            Integer seatsPerRow,
            Integer capacity,
            int seatCount,
            AdminZoneLayout layout) {}

    /**
     * @param rotationDeg chỉ có nghĩa với {@code GRID}; ba trường sau chỉ có nghĩa với {@code ARC}.
     *     Trường không dùng được trả {@code null} chứ không trả 0 — 0 là một bán kính hợp lệ về mặt
     *     kiểu, và màn hình sửa sẽ hiện nó ra như một giá trị đã khai.
     */
    public record AdminZoneLayout(
            String shape,
            double originX,
            double originY,
            Double rotationDeg,
            Double innerRadius,
            Double startAngleDeg,
            Double endAngleDeg) {

        public static AdminZoneLayout of(ZoneLayout layout) {
            if (layout == null) {
                return null;
            }
            boolean arc = layout.shape() == LayoutShape.ARC;
            return new AdminZoneLayout(
                    layout.shape().name(),
                    layout.originX(),
                    layout.originY(),
                    arc ? null : layout.rotationDeg(),
                    arc ? layout.innerRadius() : null,
                    arc ? layout.startAngleDeg() : null,
                    arc ? layout.endAngleDeg() : null);
        }
    }
}
