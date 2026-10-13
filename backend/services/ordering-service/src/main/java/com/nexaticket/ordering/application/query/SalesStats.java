// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.query;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Hình dạng số liệu thống kê bán hàng của một tổ chức.
 *
 * <p><b>Một định nghĩa cho mọi con số.</b> Đơn thuộc về kỳ theo thời điểm ĐẶT ({@code created_at}),
 * không theo thời điểm trả: phễu "tạo đơn → đã trả / quá hạn / huỷ" và doanh thu khi đó nói về cùng
 * một tập đơn. Doanh thu là tổng tiền các dòng vé của đơn {@code PAID}, đã trừ giảm giá — bằng đúng
 * {@code total_vnd} khi không lọc theo loại vé, và vẫn có nghĩa khi có lọc.
 *
 * <p>Không có hoa hồng hay phí ở đây: đó là miền tài chính của nền tảng (ADR-1010).
 */
public final class SalesStats {

    private SalesStats() {}

    /** Bộ lọc đã chuẩn hoá. Khoảng thời gian là nửa mở {@code [from, to)}; danh sách rỗng = không lọc. */
    public record Filter(
            UUID organizationId,
            Instant from,
            Instant to,
            List<UUID> eventIds,
            List<UUID> sessionIds,
            List<UUID> ticketTypeIds) {

        public Filter shifted(Instant newFrom, Instant newTo) {
            return new Filter(organizationId, newFrom, newTo, eventIds, sessionIds, ticketTypeIds);
        }
    }

    /** Kỳ báo cáo theo ngày địa phương, hai đầu đều tính. */
    public record Period(LocalDate from, LocalDate to) {}

    /**
     * @param ordersOther đơn ở trạng thái ngoài bốn nhánh của phễu (đang đối soát tay, đã hoàn)
     * @param avgSecondsToPay {@code null} khi kỳ không có đơn nào được trả
     * @param buyers số người mua khác nhau có ít nhất một đơn đã trả
     */
    public record Totals(
            long ordersTotal,
            long ordersPaid,
            long ordersAwaiting,
            long ordersExpired,
            long ordersCancelled,
            long ordersOther,
            long grossVnd,
            long ticketsSold,
            Double avgSecondsToPay,
            long buyers) {}

    public record Summary(Period period, Totals current, Period previousPeriod, Totals previous) {}

    /** Một ô thời gian; {@code bucket} là giờ địa phương Asia/Ho_Chi_Minh của đầu ô. */
    public record Point(
            LocalDateTime bucket,
            long grossVnd,
            long ticketsSold,
            long ordersCreated,
            long ordersPaid,
            long ordersExpired,
            long ordersCancelled) {

        public static Point empty(LocalDateTime bucket) {
            return new Point(bucket, 0, 0, 0, 0, 0, 0);
        }
    }

    public record Series(
            String bucket, Period period, List<Point> current, Period previousPeriod, List<Point> previous) {}

    public record SessionRow(
            UUID eventId,
            UUID eventSessionId,
            long ticketsSold,
            long grossVnd,
            long ordersTotal,
            long ordersPaid,
            long ordersAwaiting,
            long ordersExpired,
            long ordersCancelled) {}

    public record TicketTypeRow(
            UUID eventId,
            UUID eventSessionId,
            UUID ticketTypeId,
            String ticketTypeName,
            long unitPriceVnd,
            long ticketsSold,
            long grossVnd) {}

    /** @param dayOfWeek 1 = thứ Hai … 7 = Chủ nhật (ISO), theo giờ địa phương */
    public record HeatCell(int dayOfWeek, int hour, long grossVnd, long ordersPaid) {}

    public record Breakdown(Period period, List<SessionRow> sessions, List<TicketTypeRow> ticketTypes) {}

    public record Heatmap(Period period, List<HeatCell> cells) {}

    /** @param tickets số vé trong một đơn đã trả; {@code orders} là số đơn có đúng ngần ấy vé */
    public record SizeBucket(int tickets, long orders) {}

    public record Distribution(Period period, List<SizeBucket> ticketsPerOrder) {}

    /**
     * Một dòng của danh sách đơn. Không có tên hay email người mua: ordering chỉ biết mã người dùng,
     * và màn hình tự hỏi tên (đã che bớt) ở ticketing theo {@code orderId}.
     */
    public record OrderRow(
            UUID orderId,
            String orderNumber,
            UUID eventId,
            UUID eventSessionId,
            String status,
            long tickets,
            long totalVnd,
            Instant createdAt,
            Instant paidAt) {}

    /** @param total tổng số đơn khớp bộ lọc, không phải số dòng của trang này */
    public record OrderPage(Period period, List<OrderRow> rows, long total, int page, int size) {}

    /**
     * Người mua trong kỳ, tách mới và quay lại. Chỉ là số đếm — không có danh tính nào ở đây.
     *
     * @param buyers số người có ít nhất một đơn đã trả trong kỳ
     * @param returning trong số đó, những người đã có đơn đã trả ở tổ chức này TRƯỚC kỳ
     * @param repeatInPeriod số người có từ hai đơn đã trả trở lên ngay trong kỳ
     */
    public record Customers(
            Period period,
            long buyers,
            long newBuyers,
            long returning,
            long repeatInPeriod,
            List<SizeBucket> ordersPerBuyer) {}
}
