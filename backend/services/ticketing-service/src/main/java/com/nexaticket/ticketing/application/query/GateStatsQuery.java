// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application.query;

import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GateStatsQuery {

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    static final int MAX_DAYS = 731;
    static final int MAX_IDS = 200;
    static final int MAX_OVERRIDES = 50;
    /** 400 ô 15 phút là 100 giờ soát vé — đủ cho mọi suất trong một kỳ hợp lý, và vẫn vẽ được. */
    static final int MAX_TIMELINE = 400;

    static final int MAX_ORDERS = 100;

    private final GateStats.Reader reader;

    public GateStatsQuery(GateStats.Reader reader) {
        this.reader = reader;
    }

    /** Ảnh chụp hiện tại, không theo kỳ: "đang ở trong" chỉ có nghĩa ở thời điểm hỏi. */
    @Transactional(readOnly = true)
    public GateStats.Presence presence(UUID organizationId) {
        return new GateStats.Presence(reader.presenceBySession(organizationId));
    }

    /**
     * Hoạt động soát vé trong kỳ, tính theo thời điểm quét.
     *
     * @param sessionIds {@code null} hoặc rỗng là mọi suất của tổ chức
     */
    @Transactional(readOnly = true)
    public GateStats.Activity activity(UUID organizationId, LocalDate from, LocalDate to, List<UUID> sessionIds) {
        if (from == null || to == null || to.isBefore(from)) {
            throw invalid("Khoảng thời gian không hợp lệ");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw invalid("Khoảng thời gian tối đa là " + MAX_DAYS + " ngày");
        }
        List<UUID> sessions = sessionIds == null ? List.of() : List.copyOf(sessionIds);
        if (sessions.size() > MAX_IDS) {
            throw invalid("Tối đa " + MAX_IDS + " suất cho mỗi lần hỏi");
        }
        GateStats.LogFilter filter = new GateStats.LogFilter(
                organizationId,
                from.atStartOfDay(ZONE).toInstant(),
                to.plusDays(1).atStartOfDay(ZONE).toInstant(),
                sessions);

        // Hỏi thừa một ô để biết có bị cắt hay không, rồi trả theo thứ tự thời gian tăng dần.
        List<GateStats.FlowPoint> newestFirst = reader.timeline(filter, MAX_TIMELINE + 1);
        boolean truncated = newestFirst.size() > MAX_TIMELINE;
        List<GateStats.FlowPoint> timeline = newestFirst.stream()
                .limit(MAX_TIMELINE)
                .sorted(Comparator.comparing(GateStats.FlowPoint::bucket))
                .toList();

        return new GateStats.Activity(
                from,
                to,
                reader.totals(filter),
                reader.rejectedByReason(filter),
                reader.entriesByMethod(filter),
                reader.flowBySession(filter),
                reader.scanners(filter),
                reader.gates(filter),
                reader.overrides(filter, MAX_OVERRIDES),
                timeline,
                truncated);
    }

    /**
     * Tên người mua của một trang đơn hàng, ĐÃ che bớt.
     *
     * <p>Che ở đây chứ không ở trình duyệt: tên đầy đủ không bao giờ rời khỏi service, nên không có
     * cách nào đọc nó ra từ tab Network.
     */
    @Transactional(readOnly = true)
    public GateStats.Buyers buyers(UUID organizationId, List<UUID> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return new GateStats.Buyers(List.of());
        }
        if (orderIds.size() > MAX_ORDERS) {
            throw invalid("Tối đa " + MAX_ORDERS + " đơn cho mỗi lần hỏi");
        }
        return new GateStats.Buyers(reader.holderNames(organizationId, List.copyOf(orderIds)).stream()
                .map(buyer -> new GateStats.Buyer(buyer.orderId(), maskName(buyer.name())))
                .toList());
    }

    /** Vé vắng mặt theo loại vé. Người gọi nêu suất nào đã kết thúc — ticketing không biết giờ diễn. */
    @Transactional(readOnly = true)
    public GateStats.NoShows noShows(UUID organizationId, List<UUID> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return new GateStats.NoShows(List.of());
        }
        if (sessionIds.size() > MAX_IDS) {
            throw invalid("Tối đa " + MAX_IDS + " suất cho mỗi lần hỏi");
        }
        return new GateStats.NoShows(reader.noShows(organizationId, List.copyOf(sessionIds)));
    }

    /**
     * "Nguyễn Văn An" → "Nguyễn V. A.": giữ họ, các chữ còn lại chỉ còn chữ cái đầu.
     *
     * <p>Đủ để người trực tổng đài đối chiếu với tên khách đọc qua điện thoại, không đủ để nhận ra
     * một người cụ thể từ một bảng xuất ra.
     */
    static String maskName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return null;
        }
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) {
            return initial(parts[0]);
        }
        StringBuilder masked = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            masked.append(' ').append(initial(parts[i]));
        }
        return masked.toString();
    }

    private static String initial(String word) {
        return word.substring(0, word.offsetByCodePoints(0, 1)).toUpperCase() + ".";
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.Common.VALIDATION_FAILED, message);
    }
}
