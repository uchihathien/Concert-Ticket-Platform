// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.interfaces.rest;

import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.ticketing.application.query.TicketQueries;
import com.nexaticket.ticketing.application.query.TicketView;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Ví vé của khách. */
@RestController
@RequestMapping("/v1")
public class MyTicketsController {

    private final TicketQueries queries;

    public MyTicketsController(TicketQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/me/tickets")
    public List<TicketView> mine(
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        return queries.forUser(currentUser(), Math.min(limit, 100), offset);
    }

    @GetMapping("/orders/{orderId}/tickets")
    public List<TicketView> forOrder(@PathVariable UUID orderId) {
        return queries.forOrder(orderId, currentUser());
    }

    private static UUID currentUser() {
        return TenantContext.requireAuthenticated().userId().value();
    }
}
