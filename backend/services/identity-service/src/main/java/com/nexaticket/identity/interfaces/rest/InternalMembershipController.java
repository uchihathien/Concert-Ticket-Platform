// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import com.nexaticket.identity.application.query.OrganizationQueries;
import com.nexaticket.identity.application.query.UserContactView;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.annotation.PublicEndpoint;
import com.nexaticket.platform.security.tenant.MembershipLookup;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Open Host Service của Identity (context-map.md §2).
 *
 * <p>Các service khác gọi endpoint này để dựng TenantScope thay vì đọc bảng của identity. Chỉ lộ
 * trong mạng nội bộ; gateway không route ra ngoài, và {@code InternalApiFilter} đòi bí mật dùng
 * chung khi khoá đó được khai.
 *
 * <h2>Một endpoint, không phải hai</h2>
 *
 * <p>Trước đây có thêm {@code GET /internal/users/provision}: nó UPSERT vô điều kiện rồi mới tra.
 * Hai vấn đề, và cả hai đều âm thầm:
 *
 * <ul>
 *   <li>Nó là endpoint DUY NHẤT được gọi, còn {@code /internal/memberships} thì không ai gọi. Nghĩa
 *       là đường nóng của mười service đều là một lệnh ghi, mỗi 60 giây mỗi người dùng mỗi instance.
 *   <li>UPSERT đó ghi đè {@code full_name} bằng claim của Keycloak, nên tên vừa sửa ở
 *       {@code PATCH /v1/me} bị trả về bản cũ trong vòng một phút.
 * </ul>
 *
 * <p>{@code resolve} vốn đã <b>tra-hoặc-tạo</b>: {@code LocalMembershipLookup} đọc trước, chỉ ghi ở
 * lần đầu tiên, và không đụng vào bản ghi đã có. Nó làm đúng cả hai việc mà endpoint kia định làm,
 * nên endpoint kia bị bỏ.
 */
@RestController
@RequestMapping("/internal")
public class InternalMembershipController {

    private final MembershipLookup membershipLookup;
    private final OrganizationQueries queries;

    public InternalMembershipController(MembershipLookup membershipLookup, OrganizationQueries queries) {
        this.membershipLookup = membershipLookup;
        this.queries = queries;
    }

    /**
     * Tra người dùng, tạo bản ghi nếu đây là lần đầu (auth-oidc.md bước 3).
     *
     * <p>{@code email} không bắt buộc về mặt HTTP nhưng bắt buộc về mặt nghiệp vụ ở lần đầu: không
     * có nó thì {@code LocalMembershipLookup} từ chối tạo và trả về principal rỗng, kèm log nói rõ
     * lý do. Khai {@code required = true} sẽ đổi một câu trả lời rõ ràng thành 400 ở tầng khung.
     */
    @GetMapping("/memberships")
    @PublicEndpoint(reason = "Open Host Service, chỉ truy cập được trong mạng nội bộ")
    public PrincipalView resolve(
            @RequestParam String idpSubject,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String fullName,
            @RequestParam(required = false) String sid,
            @RequestParam(required = false) java.time.Instant issuedAt) {
        // `sid` và `issuedAt` là những gì service gọi tới đọc được từ token của người dùng. Chúng
        // tuỳ chọn vì không phải IdP nào cũng phát `sid`, và vì service cũ chưa gửi. Thiếu chúng
        // thì phần kiểm thu hồi phiên đơn giản là không chạy — người dùng vẫn vào được như trước.
        MembershipLookup.Principal principal =
                membershipLookup.resolve(new MembershipLookup.Claims(idpSubject, email, fullName, sid, issuedAt));
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
     * Địa chỉ nhận thư của một người dùng — notification-service gọi lúc sắp gửi.
     *
     * <p>Tên field của {@link UserContactView} là hợp đồng với {@code IdentityHttpAdapter} bên đó.
     */
    @GetMapping("/users/{userId}")
    @PublicEndpoint(reason = "Open Host Service, chỉ truy cập được trong mạng nội bộ")
    public UserContactView contact(@PathVariable UUID userId) {
        return queries.contactOf(UserId.of(userId));
    }

    public record PrincipalView(String userId, Map<String, String> memberships, boolean superAdmin) {}
}
