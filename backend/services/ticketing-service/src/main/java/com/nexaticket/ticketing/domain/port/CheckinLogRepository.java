// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.port;

import com.nexaticket.ticketing.domain.model.CheckinResult;
import com.nexaticket.ticketing.domain.model.EntryMethod;
import com.nexaticket.ticketing.domain.model.ScanDirection;
import java.time.Instant;
import java.util.List;
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
     * @param ticketId {@code null} khi mã không dẫn tới vé nào (mã giả, mã gõ sai)
     * @param organizationId tổ chức của nhân viên đang quét
     * @param note lý do từ chối, hoặc lý do nhân viên khai khi cho vào thủ công
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
            String note) {}

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
