// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.interfaces.rest;

import com.nexaticket.ticketing.application.query.TicketSearchQuery;
import com.nexaticket.ticketing.application.query.TicketSearchViews;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tra cứu vé của ban tổ chức.
 *
 * <p>Đường dẫn mang {@code /organizations/{id}} nên {@code TenantFilter} kiểm membership và trả
 * 404 trước khi request tới đây; kiểm vai trò nằm trong query.
 *
 * <p>Mọi bộ lọc đi trên query string, không trong body: đây là một phép đọc, và một bộ lọc dán
 * được cho đồng nghiệp là thứ màn hình hỗ trợ khách hàng dùng hàng ngày ("mở link này xem giúp tôi
 * vé của chị Lan").
 */
@RestController
@RequestMapping("/v1/organizations/{organizationId}/tickets")
@Validated
public class OrganizationTicketController {

    private final TicketSearchQuery tickets;

    public OrganizationTicketController(TicketSearchQuery tickets) {
        this.tickets = tickets;
    }

    /**
     * @param status trạng thái vé — VALID · CHECKED_IN · REVOKED
     * @param paymentStatus PAID · REFUNDED
     */
    @GetMapping
    public TicketSearchViews.TicketPage search(
            @PathVariable UUID organizationId,
            @RequestParam(required = false) UUID eventSessionId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String zoneCode,
            @RequestParam(required = false) @Pattern(regexp = "(?i)VALID|CHECKED_IN|REVOKED") String status,
            @RequestParam(required = false) @Pattern(regexp = "(?i)PAID|REFUNDED") String paymentStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        return tickets.search(
                organizationId,
                new TicketSearchViews.Filter(eventSessionId, query, zoneCode, status, paymentStatus, page, size));
    }
}
