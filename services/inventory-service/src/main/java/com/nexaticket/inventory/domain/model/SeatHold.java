// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate giữ chỗ.
 *
 * <p>Bất biến: chỉ giữ chỗ {@code ACTIVE} và chưa quá hạn mới chuyển thành đơn hàng được. Kiểm tra
 * hạn bằng đồng hồ truyền vào chứ không phải {@code Instant.now()}, để test tua được thời gian.
 */
public final class SeatHold {

    private final UUID id;
    private final UUID eventSessionId;
    private final UUID userId;
    private final Instant expiresAt;
    private final List<UUID> seatIds;
    private HoldStatus status;

    private SeatHold(
            UUID id, UUID eventSessionId, UUID userId, Instant expiresAt, List<UUID> seatIds, HoldStatus status) {
        this.id = id;
        this.eventSessionId = eventSessionId;
        this.userId = userId;
        this.expiresAt = expiresAt;
        this.seatIds = List.copyOf(seatIds);
        this.status = status;
    }

    public static SeatHold active(UUID id, UUID eventSessionId, UUID userId, Instant expiresAt, List<UUID> seatIds) {
        if (seatIds.isEmpty()) {
            throw new IllegalArgumentException("Giữ chỗ rỗng không có nghĩa");
        }
        return new SeatHold(id, eventSessionId, userId, expiresAt, seatIds, HoldStatus.ACTIVE);
    }

    public static SeatHold rehydrate(
            UUID id, UUID eventSessionId, UUID userId, Instant expiresAt, List<UUID> seatIds, HoldStatus status) {
        return new SeatHold(id, eventSessionId, userId, expiresAt, seatIds, status);
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isUsableAt(Instant now) {
        return status == HoldStatus.ACTIVE && !isExpiredAt(now);
    }

    /** Chỉ chủ sở hữu thao tác được. Gọi trước mọi lệnh đổi trạng thái. */
    public boolean isOwnedBy(UUID candidate) {
        return userId.equals(candidate);
    }

    /**
     * Chuyển sang đơn hàng.
     *
     * @throws IllegalStateException nếu giữ chỗ đã hết hạn hoặc đã dùng — gọi sai thứ tự là bug,
     *     không phải tình huống nghiệp vụ, nên ném chứ không trả mã lỗi
     */
    public void convert(Instant now) {
        if (!isUsableAt(now)) {
            throw new IllegalStateException("Giữ chỗ " + id + " không dùng được: status=" + status);
        }
        status = HoldStatus.CONVERTED;
    }

    public void release() {
        if (status == HoldStatus.ACTIVE) {
            status = HoldStatus.RELEASED;
        }
    }

    public void expire() {
        if (status == HoldStatus.ACTIVE) {
            status = HoldStatus.EXPIRED;
        }
    }

    public UUID id() {
        return id;
    }

    public UUID eventSessionId() {
        return eventSessionId;
    }

    public UUID userId() {
        return userId;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public List<UUID> seatIds() {
        return seatIds;
    }

    public HoldStatus status() {
        return status;
    }

    public int size() {
        return seatIds.size();
    }
}
