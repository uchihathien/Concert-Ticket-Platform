// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.interfaces.rest;

import com.nexaticket.catalog.application.command.ConfigurePurchaseLimitsHandler;
import com.nexaticket.platform.security.tenant.TenantContext;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cấu hình trần mua vé (ADR-1014).
 *
 * <p>{@code GET} trả cả <b>giá trị hiệu lực</b> lẫn <b>nguồn kế thừa</b>. Ba tầng cấu hình là ba
 * chỗ để nhìn khi debug "sao khách này không mua được", nên màn hình phải hiện rõ giá trị nào đang
 * áp dụng và nó đến từ đâu — không chỉ hiện ô nhập.
 */
@RestController
@RequestMapping("/v1")
public class PurchaseLimitController {

    private final ConfigurePurchaseLimitsHandler handler;

    public PurchaseLimitController(ConfigurePurchaseLimitsHandler handler) {
        this.handler = handler;
    }

    /** {@code null} ở một trường nghĩa là kế thừa tầng trên. */
    public record LimitsRequest(
            Integer maxSeatedPerHold,
            Integer maxStandingPerHold,
            Integer maxUnitsPerHold,
            Integer maxTicketsPerCustomer) {

        ConfigurePurchaseLimitsHandler.Limits toCommand() {
            return new ConfigurePurchaseLimitsHandler.Limits(
                    maxSeatedPerHold, maxStandingPerHold, maxUnitsPerHold, maxTicketsPerCustomer);
        }
    }

    @GetMapping("/admin/organizations/{organizationId}/purchase-limits")
    public ConfigurePurchaseLimitsHandler.EffectiveLimits organizationLimits(@PathVariable UUID organizationId) {
        return handler.effectiveFor(organizationId);
    }

    @PutMapping("/admin/organizations/{organizationId}/purchase-limits")
    public ConfigurePurchaseLimitsHandler.EffectiveLimits saveOrganizationLimits(
            @PathVariable UUID organizationId, @RequestBody LimitsRequest request) {
        handler.saveOrganizationDefaults(organizationId, request.toCommand());
        return handler.effectiveFor(organizationId);
    }

    /** Trần cứng của nền tảng — chỉ SUPER_ADMIN (ADR-1010). */
    @PutMapping("/platform/purchase-limits")
    public void savePlatformCeiling(@RequestBody LimitsRequest request) {
        TenantContext.requireSuperAdmin();
        handler.savePlatformCeiling(
                request.toCommand(),
                TenantContext.requireAuthenticated().userId().value());
    }
}
