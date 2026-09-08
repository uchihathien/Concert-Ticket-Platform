// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.command;

import com.nexaticket.catalog.application.CatalogErrorCode;
import com.nexaticket.catalog.domain.model.PublishPreflight;
import com.nexaticket.catalog.domain.model.PurchaseLimits;
import com.nexaticket.catalog.domain.model.SeatManifest;
import com.nexaticket.catalog.domain.model.SeatingPlan;
import com.nexaticket.catalog.domain.port.CatalogOutboxPort;
import com.nexaticket.catalog.domain.port.PurchaseLimitRepository;
import com.nexaticket.catalog.domain.port.SeatingPlanRepository;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publish một suất diễn: kiểm preflight, sinh danh sách chỗ, bắn sự kiện cho Inventory.
 *
 * <p>Đây là ranh giới giữa "tổ chức đang dựng sự kiện" và "hệ thống đang bán vé". Trước bước này
 * mọi thứ sửa thoải mái; sau bước này Inventory đã có tồn kho và khách có thể đang giữ chỗ.
 */
@Service
public class PublishSessionHandler {

    private static final Logger log = LoggerFactory.getLogger(PublishSessionHandler.class);

    private final SeatingPlanRepository plans;
    private final PurchaseLimitRepository limits;
    private final CatalogOutboxPort outbox;
    private final Clock clock;

    public PublishSessionHandler(
            SeatingPlanRepository plans, PurchaseLimitRepository limits, CatalogOutboxPort outbox, Clock clock) {
        this.plans = plans;
        this.limits = limits;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * @param sellableSeats số chỗ sẽ được bán, để màn hình quản trị hiện lại cho người dùng xác nhận
     */
    public record Result(UUID eventSessionId, int sellableSeats, int seatedCount, int standingCount) {}

    @Transactional
    public Result handle(UUID eventSessionId) {
        SeatingPlan plan = plans.load(eventSessionId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.SESSION_NOT_FOUND, "Event session not found"));
        var timing = plans.timingOf(eventSessionId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.SESSION_NOT_FOUND, "Event session not found"));

        var report = PublishPreflight.check(plan, timing.salesOpenAt(), timing.salesCloseAt(), timing.startsAt());
        if (!report.passed()) {
            // Trả TẤT CẢ lỗi cùng lúc: người dựng sự kiện đang ở màn hình có hàng chục ô nhập,
            // và báo từng lỗi một sẽ bắt họ đi qua bốn vòng sửa–thử.
            throw new ApiException(
                    CatalogErrorCode.PUBLISH_PREFLIGHT_FAILED,
                    "Event session is not ready to publish",
                    Map.of("problems", report.problems(), "codes", report.codes()));
        }

        // Giải quyết kế thừa trần MỘT LẦN ở đây rồi sao sang Inventory. Nhờ vậy đường giữ chỗ
        // chỉ đọc một cột thay vì tính coalesce() ở 10k đồng thời (ADR-1014 §1).
        SeatManifest.ResolvedPurchaseLimits resolved = PurchaseLimits.resolve(
                plans.sessionLimits(eventSessionId),
                limits.organizationDefaults(timing.organizationId()),
                limits.platformCeiling());

        SeatManifest manifest = plan.materialize(resolved);
        plans.markMaterialized(eventSessionId, clock.instant());
        outbox.sessionPublished(manifest);

        log.info(
                "Publish suất {}: {} chỗ ngồi, {} vé đứng",
                eventSessionId,
                manifest.totalSeatedCount(),
                manifest.totalStandingCount());

        return new Result(
                eventSessionId,
                manifest.totalSellableCount(),
                manifest.totalSeatedCount(),
                manifest.totalStandingCount());
    }

    /**
     * Kiểm mà không ghi gì — nút "Kiểm tra" ở màn hình dựng sự kiện.
     *
     * @return danh sách lỗi ở dạng DTO của tầng application; rỗng nghĩa là publish được ngay
     */
    @Transactional(readOnly = true)
    public List<Problem> dryRun(UUID eventSessionId) {
        SeatingPlan plan = plans.load(eventSessionId)
                .orElseThrow(() -> new ApiException(CatalogErrorCode.SESSION_NOT_FOUND, "Event session not found"));
        var timing = plans.timingOf(eventSessionId).orElseThrow();
        return PublishPreflight.check(plan, timing.salesOpenAt(), timing.salesCloseAt(), timing.startsAt())
                .problems()
                .stream()
                .map(problem -> new Problem(problem.code(), problem.detail()))
                .toList();
    }

    /** Một mục preflight không đạt. Mã lỗi là hợp đồng ổn định — frontend hiện hướng dẫn theo mã. */
    public record Problem(String code, String detail) {}
}
