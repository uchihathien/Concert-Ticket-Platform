// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.interfaces.rest;

import com.nexaticket.payout.application.command.ApprovePayoutHandler;
import com.nexaticket.payout.application.command.CreatePayoutBatchHandler;
import com.nexaticket.platform.security.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chi trả — <b>chỉ SUPER_ADMIN</b>.
 *
 * <p>Không có endpoint nào cho tổ chức ở service này, và đó là ranh giới cứng của kiến trúc: tổ
 * chức chỉ thấy số vé và số tiền đã bán, không thấy sổ cái, tài khoản ngân hàng hay lịch chi trả
 * (ADR-1010).
 *
 * <p>{@code requireSuperAdmin()} được gọi ở <b>từng</b> phương thức chứ không đặt một lần ở
 * cấp lớp: thêm một endpoint mới mà quên annotation ở cấp lớp là mở toang cả service, còn quên
 * một dòng gọi trong phương thức thì chỉ hở đúng phương thức đó — và dễ thấy hơn khi review.
 */
@RestController
@RequestMapping("/v1/platform/payouts")
public class PlatformPayoutController {

    private final CreatePayoutBatchHandler createBatch;
    private final ApprovePayoutHandler approvePayout;

    public PlatformPayoutController(CreatePayoutBatchHandler createBatch, ApprovePayoutHandler approvePayout) {
        this.createBatch = createBatch;
        this.approvePayout = approvePayout;
    }

    /**
     * @param holdPeriodElapsed người vận hành xác nhận đã qua hạn giữ tiền; hệ thống biết ngày
     *     diễn nhưng không biết sự kiện có bị hoãn hay đang có tranh chấp
     */
    public record CreateBatchRequest(
            @NotNull UUID organizationId, @Positive long amountVnd, boolean holdPeriodElapsed) {}

    public record BatchCreated(UUID batchId, long amountVnd, String status) {}

    public record CompleteRequest(@NotBlank String bankReference) {}

    public record RejectRequest(@NotBlank String reason) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BatchCreated create(@Valid @RequestBody CreateBatchRequest request) {
        TenantContext.requireSuperAdmin();
        var result = createBatch.handle(new CreatePayoutBatchHandler.Command(
                request.organizationId(),
                request.amountVnd(),
                TenantContext.requireAuthenticated().userId().value(),
                request.holdPeriodElapsed()));
        return new BatchCreated(result.batchId(), result.amountVnd(), result.status());
    }

    @PostMapping("/{batchId}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable UUID batchId) {
        TenantContext.requireSuperAdmin();
        approvePayout.approve(
                batchId, TenantContext.requireAuthenticated().userId().value());
    }

    @PostMapping("/{batchId}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable UUID batchId, @Valid @RequestBody RejectRequest request) {
        TenantContext.requireSuperAdmin();
        approvePayout.reject(batchId, request.reason());
    }

    @PostMapping("/{batchId}/mark-completed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markCompleted(@PathVariable UUID batchId, @Valid @RequestBody CompleteRequest request) {
        TenantContext.requireSuperAdmin();
        approvePayout.markCompleted(batchId, request.bankReference());
    }
}
