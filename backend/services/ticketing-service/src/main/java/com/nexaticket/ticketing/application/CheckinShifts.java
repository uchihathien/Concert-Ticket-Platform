// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application;

import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.security.tenant.TenantScope;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.ticketing.domain.port.CatalogPort;
import com.nexaticket.ticketing.domain.port.CheckinAssignmentRepository;
import com.nexaticket.ticketing.domain.port.CheckinLogRepository;
import com.nexaticket.ticketing.domain.port.ShiftRepository;
import com.nexaticket.ticketing.domain.port.ShiftRepository.Gate;
import com.nexaticket.ticketing.domain.port.ShiftRepository.Shift;
import com.nexaticket.ticketing.domain.port.ShiftRepository.ShiftSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ca trực soát vé: ban tổ chức xếp cổng và ca theo suất, nhân viên bấm bắt đầu / kết thúc ca.
 *
 * <h2>Ca ràng buộc lượt quét thế nào</h2>
 *
 * <p>Nhân viên CHỈ có vai soát vé quét được một suất khi họ đang có phiên ca mở ở một ca của đúng
 * suất đó, và đang trong khung giờ của ca (sớm tối đa {@code earlyStart}) — xem
 * {@link CheckinAccess#requireScannerOnShift}. Cổng ghi vào lượt quét lấy từ ca, không lấy từ máy.
 *
 * <p>Chủ tổ chức, quản trị và quản lý sự kiện ({@code CATALOG_MANAGE}) không bị ràng buộc ca: họ là
 * người đi xử lý sự cố ở mọi cổng.
 */
@Service
public class CheckinShifts {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ShiftRepository shifts;
    private final CheckinAssignmentRepository assignments;
    private final CheckinLogRepository log;
    private final CatalogPort catalog;
    private final Clock clock;
    private final Duration earlyStart;

    public CheckinShifts(
            ShiftRepository shifts,
            CheckinAssignmentRepository assignments,
            CheckinLogRepository log,
            CatalogPort catalog,
            Clock clock,
            @Value("${nexaticket.ticketing.checkin.shift-early-start:PT30M}") Duration earlyStart) {
        this.shifts = shifts;
        this.assignments = assignments;
        this.log = log;
        this.catalog = catalog;
        this.clock = clock;
        this.earlyStart = earlyStart;
    }

    /** NOT_STARTED · ACTIVE · ENDED — theo giờ của ca, không theo việc có ai bấm bắt đầu. */
    public enum ShiftState {
        NOT_STARTED,
        ACTIVE,
        ENDED
    }

    public record OnDuty(UUID userId, Instant startedAt, String deviceId) {}

    public record GateView(UUID id, UUID eventId, String name) {

        static GateView of(Gate gate) {
            return new GateView(gate.id(), gate.eventId(), gate.name());
        }
    }

    public record ShiftView(
            UUID id,
            UUID eventId,
            UUID eventSessionId,
            UUID gateId,
            String gateName,
            Instant startsAt,
            Instant endsAt,
            List<UUID> staffIds,
            ShiftState state,
            List<OnDuty> onDuty) {}

    /**
     * Ca của chính nhân viên, kèm những gì app cần để hiện mà không hỏi thêm.
     *
     * @param myStartedAt phiên ca đang mở của chính người hỏi; {@code null} nếu chưa bắt đầu
     * @param canStart đang trong khung được bấm "Bắt đầu ca" (sớm tối đa 30 phút)
     */
    public record MyShift(
            UUID id,
            UUID organizationId,
            UUID eventId,
            String eventTitle,
            UUID eventSessionId,
            Instant sessionStartsAt,
            String gateName,
            Instant startsAt,
            Instant endsAt,
            ShiftState state,
            Instant myStartedAt,
            boolean canStart) {}

    /** Bàn giao cuối ca. */
    public record Handover(
            UUID shiftId, String gateName, Instant startedAt, Instant endedAt, CheckinLogRepository.ShiftTally tally) {}

    public record ShiftDraft(UUID eventSessionId, UUID gateId, Instant startsAt, Instant endsAt, List<UUID> staffIds) {}

    // --- cổng (ban tổ chức) ---

    @Transactional(readOnly = true)
    public List<GateView> gates(UUID organizationId, UUID eventId) {
        requireManager(organizationId);
        return shifts.gatesOf(organizationId, eventId).stream()
                .map(GateView::of)
                .toList();
    }

    @Transactional
    public GateView createGate(UUID organizationId, UUID eventId, String name) {
        requireManager(organizationId);
        Gate gate = new Gate(UUID.randomUUID(), organizationId, eventId, cleanName(name), clock.instant());
        try {
            shifts.createGate(gate);
        } catch (DuplicateKeyException e) {
            throw new ApiException(TicketingErrorCode.GATE_NAME_TAKEN, "Sự kiện đã có cổng tên này");
        }
        return GateView.of(gate);
    }

    @Transactional
    public void renameGate(UUID organizationId, UUID gateId, String name) {
        requireManager(organizationId);
        gate(organizationId, gateId);
        try {
            shifts.renameGate(gateId, cleanName(name));
        } catch (DuplicateKeyException e) {
            throw new ApiException(TicketingErrorCode.GATE_NAME_TAKEN, "Sự kiện đã có cổng tên này");
        }
    }

    @Transactional
    public void deleteGate(UUID organizationId, UUID gateId) {
        requireManager(organizationId);
        gate(organizationId, gateId);
        if (!shifts.deleteGateIfUnused(gateId)) {
            throw new ApiException(TicketingErrorCode.GATE_IN_USE, "Cổng còn ca trực — xoá hoặc chuyển các ca trước");
        }
    }

    // --- ca (ban tổ chức) ---

    @Transactional(readOnly = true)
    public List<ShiftView> shiftsOfEvent(UUID organizationId, UUID eventId) {
        requireManager(organizationId);
        return views(shifts.shiftsOfEvent(organizationId, eventId));
    }

    @Transactional
    public ShiftView createShift(UUID organizationId, ShiftDraft draft) {
        requireManager(organizationId);
        Gate gate = gate(organizationId, draft.gateId());
        validate(gate, draft);
        Shift shift = new Shift(
                UUID.randomUUID(),
                organizationId,
                gate.eventId(),
                draft.eventSessionId(),
                gate.id(),
                gate.name(),
                draft.startsAt(),
                draft.endsAt(),
                List.copyOf(new LinkedHashSet<>(draft.staffIds())));
        shifts.createShift(shift);
        return views(List.of(shifts.findShift(organizationId, shift.id()).orElseThrow()))
                .get(0);
    }

    @Transactional
    public ShiftView updateShift(UUID organizationId, UUID shiftId, ShiftDraft draft) {
        requireManager(organizationId);
        Shift existing = shift(organizationId, shiftId);
        Gate gate = gate(organizationId, draft.gateId());
        if (!draft.eventSessionId().equals(existing.eventSessionId())) {
            throw new ApiException(
                    TicketingErrorCode.SHIFT_INVALID, "Không đổi được suất của một ca — xoá ca và tạo ca mới");
        }
        validate(gate, draft);
        shifts.updateShift(shiftId, gate.id(), draft.startsAt(), draft.endsAt());
        shifts.replaceStaff(shiftId, draft.staffIds());
        return views(List.of(shifts.findShift(organizationId, shiftId).orElseThrow()))
                .get(0);
    }

    @Transactional
    public void deleteShift(UUID organizationId, UUID shiftId) {
        requireManager(organizationId);
        shift(organizationId, shiftId);
        shifts.deleteShift(shiftId);
    }

    // --- nhân viên ---

    /** Ca hôm nay (giờ Việt Nam) và sắp tới của chính người gọi. */
    @Transactional(readOnly = true)
    public List<MyShift> myShifts(UUID organizationId) {
        TenantScope scope = TenantContext.requirePermission(Permission.CHECKIN_SCAN, TenantId.of(organizationId));
        Instant now = clock.instant();
        Instant startOfToday =
                LocalDate.now(clock.withZone(VIETNAM)).atStartOfDay(VIETNAM).toInstant();
        UUID me = scope.userId().value();
        List<Shift> mine = shifts.shiftsOfStaff(organizationId, me, startOfToday);

        Set<UUID> eventIds = mine.stream().map(Shift::eventId).collect(Collectors.toSet());
        Map<UUID, CatalogPort.SessionSlot> sessions = catalog.sessionsOfEvents(eventIds).orElse(List.of()).stream()
                .collect(Collectors.toMap(CatalogPort.SessionSlot::eventSessionId, Function.identity(), (a, b) -> a));

        return mine.stream()
                .map(shift -> {
                    CatalogPort.SessionSlot session = sessions.get(shift.eventSessionId());
                    Instant myStart = shifts.openSession(shift.id(), me)
                            .map(ShiftSession::startedAt)
                            .orElse(null);
                    return new MyShift(
                            shift.id(),
                            organizationId,
                            shift.eventId(),
                            session == null ? null : session.eventTitle(),
                            shift.eventSessionId(),
                            session == null ? null : session.startsAt(),
                            shift.gateName(),
                            shift.startsAt(),
                            shift.endsAt(),
                            stateOf(shift, now),
                            myStart,
                            withinStartWindow(shift, now));
                })
                .toList();
    }

    @Transactional
    public MyShift startShift(UUID organizationId, UUID shiftId, String deviceId) {
        TenantScope scope = TenantContext.requirePermission(Permission.CHECKIN_SCAN, TenantId.of(organizationId));
        UUID me = scope.userId().value();
        Shift shift = shift(organizationId, shiftId);
        if (!shift.staffIds().contains(me)) {
            throw new ApiException(TicketingErrorCode.SHIFT_NOT_FOUND, "Bạn không có trong ca này");
        }
        Instant now = clock.instant();
        if (!withinStartWindow(shift, now)) {
            throw new ApiException(
                    TicketingErrorCode.SHIFT_NOT_ACTIVE,
                    now.isBefore(shift.startsAt())
                            ? "Chưa tới giờ bắt đầu ca (được bắt đầu sớm tối đa " + earlyStart.toMinutes() + " phút)"
                            : "Ca đã kết thúc");
        }
        shifts.startSession(shiftId, me, deviceId, now);
        return myShifts(organizationId).stream()
                .filter(item -> item.id().equals(shiftId))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public Handover endShift(UUID organizationId, UUID shiftId) {
        TenantScope scope = TenantContext.requirePermission(Permission.CHECKIN_SCAN, TenantId.of(organizationId));
        UUID me = scope.userId().value();
        Shift shift = shift(organizationId, shiftId);
        ShiftSession closed = shifts.endSession(shiftId, me, clock.instant())
                .orElseThrow(() -> new ApiException(TicketingErrorCode.SHIFT_NOT_ACTIVE, "Bạn chưa bắt đầu ca này"));
        return new Handover(
                shiftId,
                shift.gateName(),
                closed.startedAt(),
                closed.endedAt(),
                log.tally(me, shift.eventSessionId(), closed.startedAt(), closed.endedAt()));
    }

    /**
     * Phiên ca đang mở của nhân viên ở suất này, nếu nó còn trong khung giờ — thứ quyết định lượt quét
     * có được đi tiếp không. Rỗng = "ngoài ca".
     */
    @Transactional(readOnly = true)
    public Optional<Shift> activeShift(UUID organizationId, UUID userId, UUID eventSessionId, Instant at) {
        return shifts.openSessionForSession(userId, eventSessionId)
                .flatMap(open -> shifts.findShift(organizationId, open.shiftId()))
                .filter(shift -> withinStartWindow(shift, at));
    }

    /**
     * Ca bao trùm một lượt quét đã xảy ra (offline, đồng bộ muộn): có phiên ca chứa thời điểm quét, và
     * thời điểm đó trong khung giờ của ca. Phiên ca đã đóng cũng tính — nhân viên có thể kết thúc ca
     * rồi mới có mạng để gửi lên.
     */
    @Transactional(readOnly = true)
    public Optional<Shift> shiftCovering(UUID organizationId, UUID userId, UUID eventSessionId, Instant at) {
        return shifts.sessionCovering(userId, eventSessionId, at)
                .flatMap(covering -> shifts.findShift(organizationId, covering.shiftId()))
                .filter(shift -> withinStartWindow(shift, at));
    }

    private boolean withinStartWindow(Shift shift, Instant at) {
        return !at.isBefore(shift.startsAt().minus(earlyStart)) && !at.isAfter(shift.endsAt());
    }

    private static ShiftState stateOf(Shift shift, Instant now) {
        if (now.isBefore(shift.startsAt())) return ShiftState.NOT_STARTED;
        return now.isAfter(shift.endsAt()) ? ShiftState.ENDED : ShiftState.ACTIVE;
    }

    private List<ShiftView> views(List<Shift> list) {
        Instant now = clock.instant();
        Map<UUID, List<OnDuty>> duty = shifts
                .openSessionsOfShifts(list.stream().map(Shift::id).toList())
                .stream()
                .collect(Collectors.groupingBy(
                        ShiftSession::shiftId,
                        Collectors.mapping(
                                open -> new OnDuty(open.userId(), open.startedAt(), open.deviceId()),
                                Collectors.toList())));
        return list.stream()
                .map(shift -> new ShiftView(
                        shift.id(),
                        shift.eventId(),
                        shift.eventSessionId(),
                        shift.gateId(),
                        shift.gateName(),
                        shift.startsAt(),
                        shift.endsAt(),
                        shift.staffIds(),
                        stateOf(shift, now),
                        duty.getOrDefault(shift.id(), List.of())))
                .toList();
    }

    /**
     * Suất phải thuộc đúng sự kiện của cổng, và mọi nhân viên trong ca phải đã được gán sự kiện đó ở
     * mục Nhân sự — ca không phải đường tắt để cấp quyền soát vé.
     */
    private void validate(Gate gate, ShiftDraft draft) {
        if (!draft.endsAt().isAfter(draft.startsAt())) {
            throw new ApiException(TicketingErrorCode.SHIFT_INVALID, "Giờ kết thúc phải sau giờ bắt đầu");
        }
        if (Duration.between(draft.startsAt(), draft.endsAt()).compareTo(Duration.ofHours(24)) > 0) {
            throw new ApiException(TicketingErrorCode.SHIFT_INVALID, "Một ca dài tối đa 24 giờ");
        }
        CatalogPort.SessionInfo session = catalog.sessionInfo(draft.eventSessionId())
                .orElseThrow(() -> new ApiException(
                        TicketingErrorCode.CATALOG_UNAVAILABLE, "Chưa đọc được suất diễn. Thử lại sau."));
        if (!gate.eventId().equals(session.eventId())) {
            throw new ApiException(TicketingErrorCode.SHIFT_INVALID, "Suất không thuộc sự kiện của cổng này");
        }
        Collection<UUID> staff = new LinkedHashSet<>(draft.staffIds());
        Set<UUID> assigned = assignments.find(gate.organizationId(), null).stream()
                .filter(a -> a.eventId().equals(gate.eventId()))
                .map(CheckinAssignmentRepository.Assignment::userId)
                .collect(Collectors.toSet());
        for (UUID userId : staff) {
            if (!assigned.contains(userId)) {
                throw new ApiException(
                        TicketingErrorCode.SHIFT_INVALID,
                        "Có nhân viên chưa được gán sự kiện này ở mục Nhân sự — gán trước rồi xếp ca");
            }
        }
    }

    private Gate gate(UUID organizationId, UUID gateId) {
        return shifts.findGate(organizationId, gateId)
                .orElseThrow(() -> new ApiException(TicketingErrorCode.GATE_NOT_FOUND, "Không có cổng này"));
    }

    private Shift shift(UUID organizationId, UUID shiftId) {
        return shifts.findShift(organizationId, shiftId)
                .orElseThrow(() -> new ApiException(TicketingErrorCode.SHIFT_NOT_FOUND, "Không có ca này"));
    }

    private static void requireManager(UUID organizationId) {
        TenantContext.requirePermission(Permission.CATALOG_MANAGE, TenantId.of(organizationId));
    }

    private static String cleanName(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > 40) {
            throw new ApiException(TicketingErrorCode.SHIFT_INVALID, "Tên cổng dài 1–40 ký tự");
        }
        return trimmed;
    }
}
