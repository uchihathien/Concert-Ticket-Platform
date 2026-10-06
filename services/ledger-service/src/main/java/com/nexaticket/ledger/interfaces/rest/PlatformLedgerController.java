// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger.interfaces.rest;

import com.nexaticket.ledger.application.query.LedgerQueries;
import com.nexaticket.platform.security.tenant.TenantContext;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Khu vực sổ cái — <b>chỉ superadmin</b>.
 *
 * <p>Không có endpoint nào cho tổ chức. Controller chỉ phụ thuộc tầng application, không chạm
 * aggregate hay repository của domain (ArchitectureRules.hexagonalLayers ép điều này).
 */
@RestController
@RequestMapping("/v1/platform")
public class PlatformLedgerController {

    private final LedgerQueries queries;

    public PlatformLedgerController(LedgerQueries queries) {
        this.queries = queries;
    }

    /** Bảng cân đối thử. {@code balanced = false} là cảnh báo mức cao nhất. */
    @GetMapping("/trial-balance")
    public LedgerQueries.TrialBalanceView trialBalance() {
        TenantContext.requireSuperAdmin();
        return queries.trialBalance();
    }

    @GetMapping("/organizations/{organizationId}/balance")
    public LedgerQueries.OrganizationBalanceView organizationBalance(@PathVariable UUID organizationId) {
        TenantContext.requireSuperAdmin();
        return queries.organizationBalance(organizationId);
    }
}
