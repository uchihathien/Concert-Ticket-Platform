// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import com.nexaticket.identity.application.command.CreateOrganization;
import com.nexaticket.identity.application.command.CreateOrganizationHandler;
import com.nexaticket.identity.application.command.OrganizationLifecycleHandler;
import com.nexaticket.identity.application.query.OrganizationQueries;
import com.nexaticket.identity.application.query.OrganizationView;
import com.nexaticket.kernel.id.TenantId;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final OrganizationLifecycleHandler lifecycle;
    private final OrganizationQueries queries;

    public PlatformOrganizationController(
            CreateOrganizationHandler createOrganization,
            OrganizationLifecycleHandler lifecycle,
            OrganizationQueries queries) {
        this.createOrganization = createOrganization;
        this.lifecycle = lifecycle;
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
     * Khoá một tổ chức.
     *
     * <p>Chỉ nền tảng làm được, và tổ chức không tự mở khoá cho mình — nếu không thì việc khoá
     * chẳng có ý nghĩa gì. Khoá KHÔNG xoá gì và không dừng việc bán vé đang diễn ra; nó chặn những
     * đường có kiểm {@code isActive()}, hiện là mời thành viên. Dừng bán là thao tác của catalog
     * (rút xuống hoặc huỷ), và gộp hai thứ vào một nút sẽ khiến một quyết định vận hành âm thầm
     * kéo theo một quyết định thương mại.
     */
    @PostMapping("/{organizationId}/suspend")
    public OrganizationSummary suspend(@PathVariable UUID organizationId) {
        lifecycle.suspend(TenantId.of(organizationId));
        return OrganizationSummary.from(queries.byId(TenantId.of(organizationId)));
    }

    @PostMapping("/{organizationId}/activate")
    public OrganizationSummary activate(@PathVariable UUID organizationId) {
        lifecycle.activate(TenantId.of(organizationId));
        return OrganizationSummary.from(queries.byId(TenantId.of(organizationId)));
    }

    /**
     * @param invitationToken token thô của lời mời chủ sở hữu, trả về đúng một lần. Ở production
     *     notification-service gửi email; token có mặt ở đây để dev và staging thử được luồng mà
     *     không cần hộp thư thật.
     */
    public record CreatedOrganizationResponse(
            String id, String slug, String name, String status, String ownerEmail, String invitationToken) {}
}
