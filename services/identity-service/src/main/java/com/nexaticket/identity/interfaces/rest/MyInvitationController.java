// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import com.nexaticket.identity.application.command.AcceptInvitationHandler;
import com.nexaticket.identity.application.query.MyInvitationView;
import com.nexaticket.identity.application.query.OrganizationQueries;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated invitation inbox for the current user's verified identity email. */
@RestController
@RequestMapping("/v1/me/invitations")
public class MyInvitationController {

    private final OrganizationQueries queries;
    private final AcceptInvitationHandler acceptInvitation;

    public MyInvitationController(OrganizationQueries queries, AcceptInvitationHandler acceptInvitation) {
        this.queries = queries;
        this.acceptInvitation = acceptInvitation;
    }

    @GetMapping
    public List<MyInvitationView> list() {
        return queries.myPendingInvitations();
    }

    @PostMapping("/{invitationId}/accept")
    public OrganizationSummary accept(@PathVariable UUID invitationId) {
        return OrganizationSummary.from(acceptInvitation.handle(invitationId));
    }
}
