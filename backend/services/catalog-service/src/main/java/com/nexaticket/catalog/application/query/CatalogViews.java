// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.query;

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

    public record AdminVenue(UUID id, String name, String city, String address, int capacity, List<AdminZone> zones) {}

    public record AdminZone(
            UUID id,
            String zoneCode,
            String name,
            String kind,
            Integer rowCount,
            Integer seatsPerRow,
            Integer capacity,
            int seatCount) {}
}
