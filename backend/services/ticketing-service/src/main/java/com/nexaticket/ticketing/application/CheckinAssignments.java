// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.application;

import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.ticketing.domain.model.AssignmentWindow;
import com.nexaticket.ticketing.domain.port.CatalogPort;
import com.nexaticket.ticketing.domain.port.CatalogPort.EventSchedule;
import com.nexaticket.ticketing.domain.port.CheckinAssignmentRepository;
import com.nexaticket.ticketing.domain.port.CheckinAssignmentRepository.Assignment;
import com.nexaticket.ticketing.domain.port.CheckinAssignmentRepository.Scope;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đọc và đặt phân công soát vé.
 *
 * <h2>Hạn của phân công</h2>
 *
 * <p>Không lưu ở đâu cả: tính từ lịch diễn của catalog bằng {@link AssignmentWindow} — suất cuối
 * trong phạm vi được gán kết thúc + 6 giờ. Hết hạn thì phân công vẫn nằm lại để xem lịch sử, nhưng
 * không còn mở cửa soát vé nữa.
 *
 * <h2>Gán = thay phần CÒN HIỆU LỰC</h2>
 *
 * <p>Màn hình gửi lên trạng thái mong muốn. Phân công còn hiệu lực mà không có trong đó bị gỡ; phân
 * công đã hết hạn không bị đụng tới — chúng là lịch sử, và màn hình cũng không còn hiện chúng để
 * mà tick lại.
 */
@Service
public class CheckinAssignments {

    private static final Logger log = LoggerFactory.getLogger(CheckinAssignments.class);

    private final CheckinAssignmentRepository repository;
    private final CatalogPort catalog;
    private final Clock clock;

    public CheckinAssignments(CheckinAssignmentRepository repository, CatalogPort catalog, Clock clock) {
        this.repository = repository;
        this.catalog = catalog;
        this.clock = clock;
    }

    /**
     * Một phân công kèm khung giờ đã tính.
     *
     * @param eventSessionId {@code null} khi gán cả sự kiện
     * @param sessions các suất trong phạm vi được gán; rỗng khi không đọc được lịch
     */
    public record Assigned(
            UUID userId,
            UUID eventId,
            UUID eventSessionId,
            String eventTitle,
            String venueName,
            Instant startsAt,
            Instant expiresAt,
            String status,
            List<SessionTime> sessions) {

        public boolean active() {
            return !AssignmentWindow.Status.EXPIRED.name().equals(status);
        }
    }

    /** Một mục cần gán. {@code eventSessionId} {@code null} là cả sự kiện. */
    public record Target(UUID eventId, UUID eventSessionId) {}

    /** Một suất trong phạm vi được gán. {@code endsAt} có thể null. */
    public record SessionTime(UUID eventSessionId, Instant startsAt, Instant endsAt) {}

    @Transactional(readOnly = true)
    public List<Assigned> list(UUID organizationId, UUID userId) {
        List<Assignment> rows = repository.find(organizationId, userId);
        Map<UUID, EventSchedule> schedules =
                schedulesOrEmpty(rows.stream().map(Assignment::eventId).toList());
        Instant now = clock.instant();
        return rows.stream()
                .map(row -> describe(row, schedules.get(row.eventId()), now))
                .toList();
    }

    /**
     * Phân công CÒN HIỆU LỰC của chính người gọi — cho app soát vé hiện "sự kiện tôi được gán".
     * Đã hết hạn thì không hiện: không còn gì để làm với chúng ở cổng.
     */
    @Transactional(readOnly = true)
    public List<Assigned> activeOf(UUID organizationId, UUID userId) {
        return list(organizationId, userId).stream().filter(Assigned::active).toList();
    }

    /**
     * Đặt lại phân công còn hiệu lực của một nhân viên thành đúng {@code wanted}.
     *
     * <p>Identity đã kiểm người này là {@code CHECKIN_STAFF} của tổ chức trước khi gọi sang. Ở đây
     * kiểm phần thuộc về catalog: mọi sự kiện phải thuộc đúng tổ chức, mọi suất phải thuộc đúng sự
     * kiện. Thiếu bước này thì một tổ chức gán được nhân viên của mình vào sự kiện của tổ chức khác.
     */
    @Transactional
    public List<Assigned> assign(UUID organizationId, UUID userId, Collection<Target> wanted, UUID assignedBy) {
        Set<Scope> target = new LinkedHashSet<>(wanted.stream()
                .map(t -> new Scope(t.eventId(), t.eventSessionId()))
                .toList());
        List<Assignment> existing = repository.find(organizationId, userId);

        Set<UUID> eventIds = new LinkedHashSet<>();
        target.forEach(scope -> eventIds.add(scope.eventId()));
        existing.forEach(row -> eventIds.add(row.eventId()));
        Map<UUID, EventSchedule> schedules = schedulesOrFail(eventIds);

        for (Scope scope : target) {
            requireBelongs(organizationId, scope, schedules.get(scope.eventId()));
        }

        Instant now = clock.instant();
        // Phạm vi đã có — kể cả đã hết hạn: gán lại một phạm vi hết hạn không sinh dòng mới, vì
        // dòng mới cũng tính ra đúng cái hạn đã qua đó.
        Set<Scope> present =
                new LinkedHashSet<>(existing.stream().map(Assignment::scope).toList());
        List<UUID> stale = existing.stream()
                .filter(row -> window(row, schedules.get(row.eventId())).activeAt(now))
                .filter(row -> !target.contains(row.scope()))
                .map(Assignment::id)
                .toList();
        List<Scope> missing =
                target.stream().filter(scope -> !present.contains(scope)).toList();

        repository.delete(organizationId, userId, stale);
        repository.insert(organizationId, userId, missing, assignedBy, now);
        return list(organizationId, userId);
    }

    /**
     * Gỡ mọi phân công còn hiệu lực — người này bị gỡ khỏi tổ chức hoặc thôi làm Staff.
     *
     * <p>Catalog không trả lời thì gỡ HẾT, kể cả phân công có thể đã hết hạn: mất một ít lịch sử
     * còn hơn để một người đã bị gỡ còn phân công soát vé.
     */
    @Transactional
    public void revokeActive(UUID organizationId, UUID userId) {
        List<Assignment> existing = repository.find(organizationId, userId);
        if (existing.isEmpty()) {
            return;
        }
        Map<UUID, EventSchedule> schedules;
        try {
            schedules = catalog.eventSchedules(
                    existing.stream().map(Assignment::eventId).distinct().toList());
        } catch (CatalogPort.CatalogUnavailableException e) {
            log.warn("Catalog không trả lời khi gỡ phân công của {}: gỡ hết", userId);
            repository.delete(
                    organizationId,
                    userId,
                    existing.stream().map(Assignment::id).toList());
            return;
        }
        Instant now = clock.instant();
        repository.delete(
                organizationId,
                userId,
                existing.stream()
                        .filter(row -> window(row, schedules.get(row.eventId())).activeAt(now))
                        .map(Assignment::id)
                        .toList());
    }

    static AssignmentWindow window(Assignment row, EventSchedule schedule) {
        return schedule == null
                ? new AssignmentWindow(null, null)
                : AssignmentWindow.of(schedule.sessions(), row.eventSessionId());
    }

    private Assigned describe(Assignment row, EventSchedule schedule, Instant now) {
        if (schedule == null) {
            return new Assigned(
                    row.userId(),
                    row.eventId(),
                    row.eventSessionId(),
                    null,
                    null,
                    null,
                    null,
                    AssignmentWindow.Status.UNKNOWN.name(),
                    List.of());
        }
        AssignmentWindow window = AssignmentWindow.of(schedule.sessions(), row.eventSessionId());
        return new Assigned(
                row.userId(),
                row.eventId(),
                row.eventSessionId(),
                schedule.eventTitle(),
                schedule.venueName(),
                window.startsAt(),
                window.expiresAt(),
                window.statusAt(now).name(),
                schedule.sessions().stream()
                        .filter(session -> row.eventSessionId() == null
                                || row.eventSessionId().equals(session.id()))
                        .map(session -> new SessionTime(session.id(), session.startsAt(), session.endsAt()))
                        .toList());
    }

    private static void requireBelongs(UUID organizationId, Scope scope, EventSchedule schedule) {
        if (schedule == null || !organizationId.equals(schedule.organizationId())) {
            throw new ApiException(
                    TicketingErrorCode.EVENT_NOT_IN_ORGANIZATION,
                    "Sự kiện không thuộc tổ chức này: " + scope.eventId());
        }
        if (scope.eventSessionId() != null
                && schedule.sessions().stream()
                        .noneMatch(session -> scope.eventSessionId().equals(session.id()))) {
            throw new ApiException(
                    TicketingErrorCode.EVENT_NOT_IN_ORGANIZATION,
                    "Suất diễn không thuộc sự kiện \"" + schedule.eventTitle() + "\"");
        }
    }

    /** Đường đọc: catalog im lặng thì vẫn trả bảng, chỉ thiếu trạng thái (UNKNOWN). */
    private Map<UUID, EventSchedule> schedulesOrEmpty(Collection<UUID> eventIds) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        try {
            return catalog.eventSchedules(eventIds);
        } catch (CatalogPort.CatalogUnavailableException e) {
            return Map.of();
        }
    }

    /** Đường ghi: không kiểm được sự kiện thuộc tổ chức nào thì KHÔNG ghi. */
    private Map<UUID, EventSchedule> schedulesOrFail(Collection<UUID> eventIds) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        try {
            return catalog.eventSchedules(eventIds);
        } catch (CatalogPort.CatalogUnavailableException e) {
            throw new ApiException(
                    TicketingErrorCode.CATALOG_UNAVAILABLE, "Chưa kiểm được sự kiện. Thử lại sau ít phút.");
        }
    }
}
