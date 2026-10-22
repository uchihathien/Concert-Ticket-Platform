// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Cổng, ca trực và phiên ca của nhân viên soát vé. */
public interface ShiftRepository {

    // --- cổng ---

    void createGate(Gate gate);

    Optional<Gate> findGate(UUID organizationId, UUID gateId);

    List<Gate> gatesOf(UUID organizationId, UUID eventId);

    void renameGate(UUID gateId, String name);

    /** @return false nếu cổng còn ca — không xoá */
    boolean deleteGateIfUnused(UUID gateId);

    // --- ca ---

    void createShift(Shift shift);

    void updateShift(UUID shiftId, UUID gateId, Instant startsAt, Instant endsAt);

    void deleteShift(UUID shiftId);

    Optional<Shift> findShift(UUID organizationId, UUID shiftId);

    List<Shift> shiftsOfEvent(UUID organizationId, UUID eventId);

    /** Ca của một nhân viên kết thúc sau {@code endsAfter}, sớm nhất trước. */
    List<Shift> shiftsOfStaff(UUID organizationId, UUID userId, Instant endsAfter);

    void replaceStaff(UUID shiftId, Collection<UUID> userIds);

    // --- phiên ca ---

    Optional<ShiftSession> openSession(UUID shiftId, UUID userId);

    /** Phiên đang mở của một nhân viên ở BẤT KỲ ca nào của suất này. */
    Optional<ShiftSession> openSessionForSession(UUID userId, UUID eventSessionId);

    /**
     * Phiên ca của nhân viên ở suất này mà {@code at} nằm trong đó (đang mở hoặc đã đóng) — để lượt
     * quét offline đồng bộ SAU khi kết thúc ca vẫn được tính vào đúng ca của nó.
     */
    Optional<ShiftSession> sessionCovering(UUID userId, UUID eventSessionId, Instant at);

    /** @return phiên vừa mở; đã có phiên đang mở thì trả phiên đó, không mở thêm */
    ShiftSession startSession(UUID shiftId, UUID userId, String deviceId, Instant at);

    /** @return phiên vừa đóng; rỗng nếu không có phiên nào đang mở */
    Optional<ShiftSession> endSession(UUID shiftId, UUID userId, Instant at);

    List<ShiftSession> openSessionsOfShifts(Collection<UUID> shiftIds);

    record Gate(UUID id, UUID organizationId, UUID eventId, String name, Instant createdAt) {}

    /** @param staffIds users.id của nhân viên trong ca */
    record Shift(
            UUID id,
            UUID organizationId,
            UUID eventId,
            UUID eventSessionId,
            UUID gateId,
            String gateName,
            Instant startsAt,
            Instant endsAt,
            List<UUID> staffIds) {}

    record ShiftSession(UUID id, UUID shiftId, UUID userId, String deviceId, Instant startedAt, Instant endedAt) {}
}
