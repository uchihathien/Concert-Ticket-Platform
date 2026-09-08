// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.interfaces.rest;

import com.nexaticket.catalog.application.command.PublishSessionHandler;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publish suất diễn.
 *
 * <p>TenantFilter đã kiểm membership từ đường dẫn, nên controller không phải kiểm lại quyền nhìn
 * thấy — chỉ quyền thao tác.
 */
@RestController
@RequestMapping("/v1/admin/sessions/{eventSessionId}")
public class AdminPublishController {

    private final PublishSessionHandler publishSession;

    public AdminPublishController(PublishSessionHandler publishSession) {
        this.publishSession = publishSession;
    }

    public record PublishResponse(UUID eventSessionId, int sellableSeats, int seatedCount, int standingCount) {}

    /** @param problems rỗng nghĩa là publish được ngay */
    public record PreflightResponse(boolean ready, List<Problem> problems) {

        public record Problem(String code, String detail) {}
    }

    /** Xem trước, không ghi gì — nút "Kiểm tra" ở màn hình dựng sự kiện. */
    @GetMapping("/publish-preflight")
    public PreflightResponse preflight(@PathVariable UUID eventSessionId) {
        var problems = publishSession.dryRun(eventSessionId).stream()
                .map(problem -> new PreflightResponse.Problem(problem.code(), problem.detail()))
                .toList();
        return new PreflightResponse(problems.isEmpty(), problems);
    }

    @PostMapping("/publish")
    public PublishResponse publish(@PathVariable UUID eventSessionId) {
        var result = publishSession.handle(eventSessionId);
        return new PublishResponse(
                result.eventSessionId(), result.sellableSeats(), result.seatedCount(), result.standingCount());
    }
}
