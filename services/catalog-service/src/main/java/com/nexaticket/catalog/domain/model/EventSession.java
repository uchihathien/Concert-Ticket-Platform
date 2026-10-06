// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Một suất diễn của sự kiện, cùng các hạng vé bán ở suất đó.
 *
 * <p>Giá gắn với suất chứ không với sự kiện: cùng một vở kịch, suất tối thứ Bảy và suất chiều thứ
 * Ba hiếm khi cùng giá. Gắn giá ở tầng sự kiện sẽ khiến mọi ban tổ chức muốn phân biệt phải tạo
 * hai sự kiện trùng tên.
 *
 * @param maxSeatedPerHold trần do ban tổ chức khai; {@code null} nghĩa là theo mặc định nền tảng.
 *     Kế thừa chỉ được giải lúc publish, xem {@link PurchaseLimits#resolve}.
 */
public record EventSession(
        UUID id,
        UUID eventId,
        Instant startsAt,
        Instant endsAt,
        Instant salesOpenAt,
        Instant salesCloseAt,
        Integer maxSeatedPerHold,
        Integer maxStandingPerHold,
        Integer maxUnitsPerHold,
        Integer maxTicketsPerCustomer,
        List<TicketType> ticketTypes) {

    public EventSession {
        if (startsAt == null || salesOpenAt == null || salesCloseAt == null) {
            throw new IllegalArgumentException("Suất diễn phải có giờ diễn và cửa bán");
        }
        ticketTypes = List.copyOf(ticketTypes);
    }

    public static EventSession create(
            UUID eventId,
            Instant startsAt,
            Instant endsAt,
            Instant salesOpenAt,
            Instant salesCloseAt,
            Integer maxSeatedPerHold,
            Integer maxStandingPerHold,
            Integer maxUnitsPerHold,
            Integer maxTicketsPerCustomer) {
        return new EventSession(
                UUID.randomUUID(),
                eventId,
                startsAt,
                endsAt,
                salesOpenAt,
                salesCloseAt,
                maxSeatedPerHold,
                maxStandingPerHold,
                maxUnitsPerHold,
                maxTicketsPerCustomer,
                List.of());
    }

    /**
     * Cửa bán có hợp lệ không.
     *
     * <p>Không cấm cửa bán kéo dài quá giờ diễn: bán vé cho người đến muộn là chuyện bình thường ở
     * nhiều loại sự kiện. Chỉ cấm cửa bán <b>mở</b> sau khi suất đã diễn xong, vì khi đó không còn
     * gì để bán.
     */
    public boolean hasValidSalesWindow() {
        return salesCloseAt.isAfter(salesOpenAt) && salesOpenAt.isBefore(startsAt);
    }

    public boolean sellsSomething() {
        return !ticketTypes.isEmpty();
    }

    /** Giá thấp nhất của suất, dùng cho nhãn "từ 500.000đ". Không có hạng vé nào thì trả null. */
    public Long fromPriceVnd() {
        return ticketTypes.stream().mapToLong(TicketType::priceVnd).min().stream()
                .boxed()
                .findFirst()
                .orElse(null);
    }
}
