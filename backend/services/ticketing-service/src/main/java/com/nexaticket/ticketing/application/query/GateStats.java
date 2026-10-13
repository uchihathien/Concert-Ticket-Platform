// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application.query;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Hình dạng số liệu soát vé tổng hợp của một tổ chức. */
public final class GateStats {

    private GateStats() {}

    /**
     * Hiện diện của một suất.
     *
     * @param ticketsIssued vé còn hiệu lực (không tính vé đã thu hồi)
     * @param arrived vé đã vào cửa ít nhất một lần — tử số của "tỉ lệ đến"
     * @param inside vé đang ở trong lúc hỏi
     * @param entries tổng số lần vào, gồm cả vào lại
     */
    public record SessionPresence(UUID eventSessionId, long ticketsIssued, long arrived, long inside, long entries) {}

    public record Presence(List<SessionPresence> sessions) {}

    /** Bộ lọc nhật ký soát vé. Khoảng thời gian nửa mở {@code [from, to)}; danh sách rỗng = mọi suất. */
    public record LogFilter(UUID organizationId, Instant from, Instant to, List<UUID> sessionIds) {}

    /**
     * @param entries lượt vào được chấp nhận, gồm cả vào lại
     * @param uniqueTickets số vé khác nhau đã vào trong kỳ
     * @param exits lượt ra được ghi nhận
     * @param rejected lượt quét bị từ chối, cả chiều vào lẫn chiều ra
     */
    public record LogTotals(long scans, long entries, long uniqueTickets, long exits, long rejected) {}

    public record Count(String key, long count) {}

    /**
     * Nhịp vào cửa của một suất.
     *
     * <p>Ba mốc đều tính trên lần vào ĐẦU TIÊN của mỗi vé: một người ra hút thuốc rồi vào lại lúc
     * giữa buổi không phải là "người đến muộn nhất".
     */
    public record SessionFlow(
            UUID eventSessionId,
            long entries,
            long uniqueTickets,
            long exits,
            long rejected,
            Instant firstEntryAt,
            Instant lastEntryAt,
            Instant averageEntryAt) {}

    /**
     * Một người quét (nhân viên hoặc ban tổ chức tự quét).
     *
     * @param activeMinutes số phút có ít nhất một lượt quét — mẫu số của "vé/phút". Dùng phút có
     *     hoạt động chứ không phải khoảng từ lượt đầu tới lượt cuối: một người quét 10 vé lúc mở cổng
     *     và 10 vé lúc tan không làm việc suốt ba tiếng ở giữa
     */
    public record ScannerRow(
            UUID userId,
            long scans,
            long accepted,
            long rejected,
            long exits,
            long overrides,
            long activeMinutes,
            Instant firstScanAt,
            Instant lastScanAt) {}

    public record GateRow(String gate, long scans, long accepted, long rejected, long exits) {}

    /** Một lượt cho vào thủ công. Không có tên khách — chỉ ai làm, khi nào, vì sao. */
    public record Override(
            UUID eventSessionId, UUID scannedBy, Instant scannedAt, String gate, String reason, String ticketCode) {}

    /** Một ô 15 phút, đầu ô theo giờ Việt Nam. */
    public record FlowPoint(LocalDateTime bucket, long entries, long exits, long rejected) {}

    public record Activity(
            LocalDate from,
            LocalDate to,
            LogTotals totals,
            List<Count> rejectedByReason,
            List<Count> entriesByMethod,
            List<SessionFlow> sessions,
            List<ScannerRow> scanners,
            List<GateRow> gates,
            List<Override> overrides,
            List<FlowPoint> timeline,
            boolean timelineTruncated) {}

    /** Tên người mua của một đơn, đã che bớt. */
    public record Buyer(UUID orderId, String name) {}

    public record Buyers(List<Buyer> buyers) {}

    /** Vé chưa từng vào cửa của một suất, gom theo loại vé. */
    public record NoShowRow(UUID eventSessionId, String ticketTypeName, long tickets) {}

    public record NoShows(List<NoShowRow> rows) {}

    /**
     * Một dòng của danh sách người mua xuất ra. Có TÊN ĐẦY ĐỦ của người giữ vé — chỉ đi qua
     * {@code BuyerExportQuery}, nơi mỗi lượt xuất được ghi nhật ký. Không có email hay số điện thoại.
     */
    public record BuyerExportRow(
            String ticketCode,
            String holderName,
            UUID eventSessionId,
            String ticketTypeName,
            String zoneCode,
            String seatLabel,
            String status,
            Instant issuedAt) {}

    public record BuyerExport(List<BuyerExportRow> rows, Instant exportedAt) {}

    /** Cổng đọc; mọi phép gộp chạy trong database. */
    public interface Reader {
        List<SessionPresence> presenceBySession(UUID organizationId);

        LogTotals totals(LogFilter filter);

        List<Count> rejectedByReason(LogFilter filter);

        List<Count> entriesByMethod(LogFilter filter);

        List<SessionFlow> flowBySession(LogFilter filter);

        List<ScannerRow> scanners(LogFilter filter);

        List<GateRow> gates(LogFilter filter);

        List<Override> overrides(LogFilter filter, int limit);

        /** Ô 15 phút có hoạt động, mới nhất trước, tối đa {@code limit} ô. */
        List<FlowPoint> timeline(LogFilter filter, int limit);

        /** Tên người giữ vé (chưa che) của các đơn thuộc tổ chức; đơn không có vé thì không có mặt. */
        List<Buyer> holderNames(UUID organizationId, List<UUID> orderIds);

        /** Vé còn hiệu lực chưa từng vào cửa, của những suất được nêu (đều phải thuộc tổ chức). */
        List<NoShowRow> noShows(UUID organizationId, List<UUID> sessionIds);

        /** Tối đa {@code limit} vé còn hiệu lực; {@code sessionIds} rỗng = mọi suất của tổ chức. */
        List<BuyerExportRow> buyersForExport(UUID organizationId, List<UUID> sessionIds, int limit);
    }
}
