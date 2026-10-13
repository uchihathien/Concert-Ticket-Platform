// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ordering.application.query;

import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thống kê bán hàng của tổ chức.
 *
 * <p>Ngày được hiểu theo {@code Asia/Ho_Chi_Minh}: "hôm nay" của ban tổ chức kết thúc lúc nửa đêm
 * giờ Việt Nam, không phải 07:00 sáng hôm sau như khi gom theo UTC.
 *
 * <p>Mọi yêu cầu đều bị chặn độ dài kỳ. Không có chặn thì một tham số {@code from=1900-01-01} biến
 * một trang thống kê thành một lượt quét toàn bảng đơn hàng.
 */
@Service
public class SalesStatsQuery {

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    static final int MAX_DAYS = 731;
    /** Gom theo giờ quá 14 ngày là hơn 336 ô — không còn đọc được, và chỉ tốn công database. */
    static final int MAX_DAYS_HOURLY = 14;

    static final int MAX_IDS = 200;

    static final int MAX_PAGE_SIZE = 100;
    /** Trang sâu là OFFSET lớn, tức là database đọc rồi bỏ từng ấy dòng. Chặn ở 10.000 đơn đầu. */
    static final int MAX_PAGE = 100;

    private static final List<String> ORDER_STATUSES =
            List.of("AWAITING_PAYMENT", "PAID", "EXPIRED", "CANCELLED", "REFUNDED", "MANUAL_REVIEW");

    private final SalesStatsReader reader;

    public SalesStatsQuery(SalesStatsReader reader) {
        this.reader = reader;
    }

    /** Tham số thô từ controller; {@code null} ở danh sách nghĩa là không lọc. */
    public record Request(
            UUID organizationId,
            LocalDate from,
            LocalDate to,
            List<UUID> eventIds,
            List<UUID> sessionIds,
            List<UUID> ticketTypeIds) {}

    @Transactional(readOnly = true)
    public SalesStats.Summary summary(Request request, boolean compare) {
        SalesStats.Period period = period(request, MAX_DAYS);
        SalesStats.Filter filter = filter(request, period);
        if (!compare) {
            return new SalesStats.Summary(period, reader.totals(filter), null, null);
        }
        SalesStats.Period before = previous(period);
        return new SalesStats.Summary(period, reader.totals(filter), before, reader.totals(filter(request, before)));
    }

    @Transactional(readOnly = true)
    public SalesStats.Series series(Request request, String bucket, boolean compare) {
        String unit = bucket == null ? "day" : bucket.toLowerCase();
        if (!List.of("hour", "day", "week").contains(unit)) {
            throw invalid("bucket phải là hour, day hoặc week");
        }
        SalesStats.Period period = period(request, "hour".equals(unit) ? MAX_DAYS_HOURLY : MAX_DAYS);
        List<SalesStats.Point> current = filled(reader.series(filter(request, period), unit), period, unit);
        if (!compare) {
            return new SalesStats.Series(unit, period, current, null, null);
        }
        SalesStats.Period before = previous(period);
        return new SalesStats.Series(
                unit, period, current, before, filled(reader.series(filter(request, before), unit), before, unit));
    }

    @Transactional(readOnly = true)
    public SalesStats.Breakdown breakdown(Request request) {
        SalesStats.Period period = period(request, MAX_DAYS);
        SalesStats.Filter filter = filter(request, period);
        return new SalesStats.Breakdown(period, reader.bySession(filter), reader.byTicketType(filter));
    }

    @Transactional(readOnly = true)
    public SalesStats.Heatmap heatmap(Request request) {
        SalesStats.Period period = period(request, MAX_DAYS);
        return new SalesStats.Heatmap(period, reader.heatmap(filter(request, period)));
    }

    @Transactional(readOnly = true)
    public SalesStats.Distribution distribution(Request request) {
        SalesStats.Period period = period(request, MAX_DAYS);
        return new SalesStats.Distribution(period, reader.ticketsPerOrder(filter(request, period)));
    }

    /**
     * Danh sách đơn, mới nhất trước.
     *
     * @param statuses lọc theo trạng thái đơn; {@code null} hoặc rỗng là mọi trạng thái
     * @param search tiền tố của mã đơn — người trực tổng đài gõ vài ký tự đầu khách đọc cho
     */
    @Transactional(readOnly = true)
    public SalesStats.OrderPage orders(Request request, List<String> statuses, String search, int page, int size) {
        SalesStats.Period period = period(request, MAX_DAYS);
        SalesStats.Filter filter = filter(request, period);
        List<String> wanted = statuses == null
                ? List.of()
                : statuses.stream().map(String::toUpperCase).distinct().toList();
        if (!ORDER_STATUSES.containsAll(wanted)) {
            throw invalid("Trạng thái đơn không hợp lệ");
        }
        String prefix = search == null || search.isBlank() ? null : search.trim();
        if (prefix != null && prefix.length() > 40) {
            throw invalid("Từ khoá tìm kiếm quá dài");
        }
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int safePage = Math.clamp(page, 0, MAX_PAGE);
        return new SalesStats.OrderPage(
                period,
                reader.orders(filter, wanted, prefix, safeSize, safePage * safeSize),
                reader.countOrders(filter, wanted, prefix),
                safePage,
                safeSize);
    }

    @Transactional(readOnly = true)
    public SalesStats.Customers customers(Request request) {
        SalesStats.Period period = period(request, MAX_DAYS);
        SalesStats.Filter filter = filter(request, period);
        long[] counts = reader.buyerCounts(filter);
        return new SalesStats.Customers(
                period, counts[0], counts[0] - counts[1], counts[1], counts[2], reader.ordersPerBuyer(filter));
    }

    private static SalesStats.Period period(Request request, int maxDays) {
        if (request.from() == null || request.to() == null) {
            throw invalid("Thiếu from hoặc to");
        }
        if (request.to().isBefore(request.from())) {
            throw invalid("to không được trước from");
        }
        long days = ChronoUnit.DAYS.between(request.from(), request.to()) + 1;
        if (days > maxDays) {
            throw invalid("Khoảng thời gian tối đa là " + maxDays + " ngày");
        }
        return new SalesStats.Period(request.from(), request.to());
    }

    /** Kỳ liền trước, cùng số ngày: 7 ngày gần nhất được so với 7 ngày trước đó. */
    private static SalesStats.Period previous(SalesStats.Period period) {
        long days = ChronoUnit.DAYS.between(period.from(), period.to()) + 1;
        return new SalesStats.Period(
                period.from().minusDays(days), period.from().minusDays(1));
    }

    private static SalesStats.Filter filter(Request request, SalesStats.Period period) {
        return new SalesStats.Filter(
                request.organizationId(),
                period.from().atStartOfDay(ZONE).toInstant(),
                period.to().plusDays(1).atStartOfDay(ZONE).toInstant(),
                ids(request.eventIds()),
                ids(request.sessionIds()),
                ids(request.ticketTypeIds()));
    }

    private static List<UUID> ids(List<UUID> raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw.size() > MAX_IDS) {
            throw invalid("Tối đa " + MAX_IDS + " giá trị cho mỗi bộ lọc");
        }
        return List.copyOf(raw);
    }

    /**
     * Lấp các ô không có đơn bằng số 0.
     *
     * <p>Database chỉ trả ô có dữ liệu. Để nguyên thì biểu đồ đường nối thẳng hai ngày có đơn và vẽ
     * mất những ngày ở giữa không bán được gì — đúng thứ ban tổ chức cần nhìn thấy.
     */
    private static List<SalesStats.Point> filled(List<SalesStats.Point> rows, SalesStats.Period period, String unit) {
        Map<LocalDateTime, SalesStats.Point> byBucket =
                rows.stream().collect(Collectors.toMap(SalesStats.Point::bucket, Function.identity()));
        LocalDateTime cursor =
                switch (unit) {
                    case "week" -> period.from()
                            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                            .atStartOfDay();
                    default -> period.from().atStartOfDay();
                };
        LocalDateTime end = period.to().plusDays(1).atStartOfDay();
        List<SalesStats.Point> result = new ArrayList<>();
        while (cursor.isBefore(end)) {
            SalesStats.Point point = byBucket.get(cursor);
            result.add(point == null ? SalesStats.Point.empty(cursor) : point);
            cursor = switch (unit) {
                case "hour" -> cursor.plusHours(1);
                case "week" -> cursor.plusWeeks(1);
                default -> cursor.plusDays(1);};
        }
        return result;
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.Common.VALIDATION_FAILED, message);
    }
}
