// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.analytics.domain.model;

import java.util.UUID;

/**
 * Phần đóng góp của một sự kiện vào read model.
 *
 * <p>Là <b>delta</b> chứ không phải trạng thái tuyệt đối, và đó là điều khiến consumer không phụ
 * thuộc thứ tự (ADR-1009): cộng các delta lại cho cùng một tổng bất kể chúng đến theo thứ tự nào.
 * Nếu mỗi message mang "tổng số vé đã bán tính đến lúc này" thì message đến trễ sẽ ghi đè một con
 * số mới hơn bằng một con số cũ.
 *
 * <p>Cố ý <b>không</b> có trường hoa hồng: tổ chức chỉ thấy số vé và số tiền đã bán (ADR-1010), và
 * một trường tồn tại là một trường sẽ lọt ra API sau vài lần sửa vội.
 */
public record SalesDelta(
        UUID eventSessionId,
        UUID eventId,
        UUID organizationId,
        int ticketsDelta,
        long grossDeltaVnd,
        int ordersPaidDelta,
        int ordersExpiredDelta,
        int ordersCancelledDelta) {

    public static SalesDelta orderPaid(
            UUID eventSessionId, UUID eventId, UUID organizationId, int ticketCount, long totalVnd) {
        return new SalesDelta(eventSessionId, eventId, organizationId, ticketCount, totalVnd, 1, 0, 0);
    }

    public static SalesDelta orderExpired(UUID eventSessionId, UUID eventId, UUID organizationId) {
        return new SalesDelta(eventSessionId, eventId, organizationId, 0, 0L, 0, 1, 0);
    }

    public static SalesDelta orderCancelled(UUID eventSessionId, UUID eventId, UUID organizationId) {
        return new SalesDelta(eventSessionId, eventId, organizationId, 0, 0L, 0, 0, 1);
    }

    /**
     * Hoàn tiền: trừ ngược lại đúng phần đã cộng.
     *
     * <p>Trừ chứ không xoá dòng: một suất diễn có vé bị hoàn vẫn phải hiện trong báo cáo với số
     * đúng, và xoá đi sẽ làm nó biến mất khỏi dashboard của tổ chức.
     */
    public static SalesDelta refunded(
            UUID eventSessionId, UUID eventId, UUID organizationId, int ticketCount, long totalVnd) {
        return new SalesDelta(eventSessionId, eventId, organizationId, -ticketCount, -totalVnd, -1, 0, 0);
    }
}
