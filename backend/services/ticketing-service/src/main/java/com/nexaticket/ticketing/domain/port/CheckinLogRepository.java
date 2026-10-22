// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.port;

import com.nexaticket.ticketing.domain.model.CheckinResult;
import com.nexaticket.ticketing.domain.model.EntryMethod;
import com.nexaticket.ticketing.domain.model.ScanDirection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Nhật ký mọi lần quét, kể cả những lần bị từ chối. */
public interface CheckinLogRepository {

    void record(Scan scan);

    /**
     * Lịch sử quét của một suất, mới nhất trước.
     *
     * @param query lọc theo tên khách, mã vé hoặc mã ghế; {@code null} là không lọc
     */
    List<Entry> history(UUID organizationId, UUID eventSessionId, String query, int limit, int offset);

    /**
     * Tổng kết lượt quét của MỘT nhân viên ở một suất trong một khoảng thời gian — bàn giao cuối ca.
     * Tính theo thời điểm quét ({@code scanned_at}), nên lượt quét offline đồng bộ muộn vẫn rơi
     * đúng vào ca của nó.
     */
    ShiftTally tally(UUID staffId, UUID eventSessionId, Instant from, Instant to);

    /**
     * @param entries lượt Vào được chấp nhận (gồm cả vào lại và cho vào thủ công)
     * @param exits lượt Ra được ghi nhận
     * @param rejected lượt bị từ chối, cả hai chiều
     * @param overrides lượt cho vào thủ công
     * @param offline lượt quét lúc máy mất mạng, đồng bộ sau
     * @param lookups lượt cho vào bằng tra cứu tên / email / SĐT
     */
    record ShiftTally(int entries, int exits, int rejected, int overrides, int offline, int lookups) {}

    /**
     * @param ticketId {@code null} khi mã không dẫn tới vé nào (mã giả, mã gõ sai)
     * @param organizationId tổ chức của nhân viên đang quét
     * @param note lý do từ chối, hoặc lý do nhân viên khai khi cho vào thủ công
     * @param scannedAt lúc máy quét (lượt offline); {@code null} là bây giờ
     * @param conflict máy đã cho vào lúc offline nhưng server không đồng ý
     */
    record Scan(
            UUID ticketId,
            UUID eventSessionId,
            UUID organizationId,
            UUID staffId,
            String deviceId,
            ScanDirection direction,
            String gate,
            EntryMethod method,
            CheckinResult result,
            String note,
            Instant scannedAt,
            boolean offline,
            UUID clientScanId,
            String deviceResult,
            boolean conflict) {}

    /** Lượt quét offline đã ghi với id máy này, nếu có — để gửi lại không thành ghi hai lần. */
    Optional<Previous> findByClientScanId(UUID clientScanId);

    record Previous(UUID ticketId, String result, String note, boolean conflict) {}

    /** Lượt quét offline bị đánh dấu xung đột của một suất, mới nhất trước — để ban tổ chức xem lại. */
    List<Entry> conflicts(UUID organizationId, UUID eventSessionId, int limit);

    record Entry(
            UUID id,
            UUID ticketId,
            Instant scannedAt,
            String direction,
            String result,
            String method,
            String gate,
            String note,
            UUID scannedBy,
            String holderName,
            String seatCode,
            String seatLabel,
            String ticketTypeName) {}
}
