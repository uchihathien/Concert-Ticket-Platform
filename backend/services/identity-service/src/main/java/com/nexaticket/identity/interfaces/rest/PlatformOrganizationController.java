// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import com.nexaticket.identity.application.command.CreateOrganization;
import com.nexaticket.identity.application.command.CreateOrganizationHandler;
import com.nexaticket.identity.application.query.OrganizationQueries;
import com.nexaticket.identity.application.query.OrganizationView;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Khu vực superadmin.
 *
 * <p>ADR-1010: chỉ superadmin tạo được tổ chức. Không tồn tại endpoint tự tạo.
 */
@RestController
@RequestMapping("/v1/platform/organizations")
public class PlatformOrganizationController {

    private final CreateOrganizationHandler createOrganization;
    private final OrganizationQueries queries;

    public PlatformOrganizationController(CreateOrganizationHandler createOrganization, OrganizationQueries queries) {
        this.createOrganization = createOrganization;
        this.queries = queries;
    }

    @PostMapping
    public ResponseEntity<CreatedOrganizationResponse> create(@Valid @RequestBody CreateOrganization command) {
        CreateOrganizationHandler.Result result = createOrganization.handle(command);
        OrganizationView org = result.organization();
        return ResponseEntity.created(URI.create("/v1/organizations/" + org.id()))
                .body(new CreatedOrganizationResponse(
                        org.id(),
                        org.slug(),
                        org.name(),
                        org.status(),
                        command.ownerEmail(),
                        result.invitationToken()));
    }

    @GetMapping
    public List<OrganizationSummary> list(
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        return queries.all(limit, offset).stream()
                .map(OrganizationSummary::from)
                .toList();
    }

    /**
     * @param invitationToken token thô của lời mời chủ sở hữu, trả về đúng một lần. Ở production
     *     notification-service gửi email; token có mặt ở đây để dev và staging thử được luồng mà
     *     không cần hộp thư thật.
     */
    public record CreatedOrganizationResponse(
            String id, String slug, String name, String status, String ownerEmail, String invitationToken) {}
}
