// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import com.nexaticket.identity.application.command.ProvisionUserHandler;
import com.nexaticket.platform.security.annotation.PublicEndpoint;
import com.nexaticket.platform.security.tenant.MembershipLookup;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Open Host Service của Identity (context-map.md §2).
 *
 * <p>Các service khác gọi endpoint này để dựng TenantScope thay vì đọc bảng của identity. Chỉ lộ
 * trong mạng nội bộ; gateway không route ra ngoài.
 */
@RestController
@RequestMapping("/internal")
public class InternalMembershipController {

    private final MembershipLookup membershipLookup;
    private final ProvisionUserHandler provisionUser;

    public InternalMembershipController(MembershipLookup membershipLookup, ProvisionUserHandler provisionUser) {
        this.membershipLookup = membershipLookup;
        this.provisionUser = provisionUser;
    }

    @GetMapping("/memberships")
    @PublicEndpoint(reason = "Open Host Service, chỉ truy cập được trong mạng nội bộ")
    public PrincipalView resolve(
            @RequestParam String idpSubject,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String fullName) {
        MembershipLookup.Principal principal =
                membershipLookup.resolve(new MembershipLookup.Claims(idpSubject, email, fullName));
        if (principal == null) {
            return new PrincipalView(null, Map.of(), false);
        }
        return new PrincipalView(
                principal.userId().toString(),
                principal.memberships().entrySet().stream()
                        .collect(Collectors.toMap(
                                e -> e.getKey().toString(), e -> e.getValue().name())),
                principal.superAdmin());
    }

    /**
     * Tạo bản ghi user ở lần request đầu tiên sau khi đăng nhập OIDC (auth-oidc.md bước 3). Gateway
     * gọi endpoint này sau khi xác thực JWT thành công.
     */
    @GetMapping("/users/provision")
    @PublicEndpoint(reason = "Open Host Service, chỉ truy cập được trong mạng nội bộ")
    public PrincipalView provision(
            @RequestParam String idpSubject,
            @RequestParam String email,
            @RequestParam(required = false) String fullName) {
        provisionUser.handle(idpSubject, email, fullName);
        return resolve(idpSubject, email, fullName);
    }

    public record PrincipalView(String userId, Map<String, String> memberships, boolean superAdmin) {}
}
