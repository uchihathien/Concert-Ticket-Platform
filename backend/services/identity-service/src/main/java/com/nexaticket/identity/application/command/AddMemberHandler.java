// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.model.Membership;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.port.IdentityProviderPort;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.identity.domain.port.UserRepository.UserRecord;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.platform.outbox.OutboxWriter;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.security.tenant.TenantScope;
import com.nexaticket.platform.web.error.ApiException;
import com.nexaticket.platform.web.error.ErrorCode;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ban tổ chức thêm một tài khoản NexaTicket <b>đã có sẵn</b> vào tổ chức — luồng thay cho việc
 * cấp tài khoản nhân viên.
 *
 * <h2>Quy trình</h2>
 *
 * <ol>
 *   <li>Nhân viên tự đăng ký tài khoản như khách bình thường (email, có thể bằng Google).
 *   <li>Ban tổ chức nhập <b>đúng</b> email hoặc số điện thoại của họ, xem tên, chọn vai, bấm Thêm.
 *   <li>Membership có hiệu lực ngay: quyền được tra từ database ở mỗi request ({@code /v1/me/permissions}
 *       và {@code TenantFilter}), không nằm trong token, nên không phải đụng tới Keycloak.
 * </ol>
 *
 * <h2>Vì sao chỉ khớp chính xác</h2>
 *
 * <p>Ô tìm này mở cho mọi quản trị viên của mọi tổ chức. Cho tìm mờ ("nguyen", "@gmail") là biến nó
 * thành công cụ liệt kê người dùng của cả nền tảng. Khớp chính xác thì người tìm phải biết sẵn
 * email/số điện thoại — tức là họ vốn đã quen người đó — và kết quả chỉ xác nhận lại tên. Email và
 * số điện thoại trả về đều bị che một phần.
 *
 * <h2>Không tạo tài khoản</h2>
 *
 * <p>Không gọi Keycloak để tạo hay sửa gì. Keycloak chỉ được <b>hỏi</b> trong một trường hợp: email
 * có tài khoản Keycloak nhưng chưa từng gọi API nào của hệ thống, nên chưa có bản ghi ở bảng
 * {@code users}. Khi đó id Keycloak chính là {@code sub} mà token của họ sẽ mang, nên dựng bản ghi
 * với đúng {@code sub} đó là an toàn — khác hẳn việc bịa một {@code idp_subject} giả mà
 * {@link GrantMembershipHandler} cảnh báo.
 */
@Service
public class AddMemberHandler {

    private static final Logger log = LoggerFactory.getLogger(AddMemberHandler.class);

    /** Đủ để loại chuỗi rõ ràng không phải email; Keycloak mới là nơi quyết định email hợp lệ. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** Số Việt Nam: 10 số dạng nội địa, hoặc 11 số khi viết 84… */
    private static final Pattern PHONE_DIGITS = Pattern.compile("^(0\\d{9}|84\\d{9})$");

    /** Routing key của sự kiện "được thêm vào tổ chức" — notification-service gửi thư báo. */
    public static final String MEMBER_ADDED = "member.added";

    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final IdentityProviderPort identityProvider;
    private final OutboxWriter outbox;
    private final AuditLogger audit;
    private final Clock clock;

    public AddMemberHandler(
            OrganizationRepository organizations,
            UserRepository users,
            IdentityProviderPort identityProvider,
            OutboxWriter outbox,
            AuditLogger audit,
            Clock clock) {
        this.organizations = organizations;
        this.users = users;
        this.identityProvider = identityProvider;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Một tài khoản tìm thấy, đủ để người thêm nhận ra đúng người — không hơn.
     *
     * @param maskedEmail email đã che, ví dụ {@code ng*****@gmail.com}
     * @param maskedPhone {@code null} khi người đó chưa khai số điện thoại
     * @param currentRole vai trò hiện tại trong tổ chức này, {@code null} nếu chưa là thành viên
     */
    public record Candidate(
            String userId,
            String fullName,
            String maskedEmail,
            String maskedPhone,
            boolean alreadyMember,
            String currentRole) {}

    /** Tìm theo email hoặc số điện thoại chính xác. Không thấy thì {@code USER_NOT_REGISTERED}. */
    @Transactional
    public Candidate find(TenantId organizationId, String contact) {
        TenantContext.requirePermission(Permission.ORG_MEMBERS_MANAGE, organizationId);
        Organization organization = load(organizationId);
        UserRecord user = resolve(contact);
        Optional<Membership> membership = organization.findMember(user.id());
        return new Candidate(
                user.id().toString(),
                user.fullName(),
                maskEmail(user.email()),
                user.phone() == null ? null : maskPhone(user.phone()),
                membership.isPresent(),
                membership.map(m -> m.role().name()).orElse(null));
    }

    /**
     * Thêm người có {@code contact} vào tổ chức với {@code role}.
     *
     * <p>Nhận lại {@code contact} chứ không nhận {@code userId} từ bước tìm: bước tìm và bước thêm là
     * hai request, và tra lại ở đây nghĩa là không ai gửi thẳng một {@code userId} bất kỳ để thêm
     * một người mà họ không biết email hay số điện thoại.
     */
    @Transactional
    public Candidate add(TenantId organizationId, String contact, Role role) {
        TenantScope actor = TenantContext.requirePermission(Permission.ORG_MEMBERS_MANAGE, organizationId);
        if (role == Role.SUPER_ADMIN || role == Role.CUSTOMER) {
            throw new ApiException(ErrorCode.Common.VALIDATION_FAILED, "Vai trò không thuộc phạm vi tổ chức: " + role);
        }
        if (role == Role.ORG_OWNER && !actor.superAdmin() && actor.roleIn(organizationId) != Role.ORG_OWNER) {
            // Cùng luật với ManageMembersHandler: cho ORG_ADMIN thêm OWNER là cho họ tự nâng quyền qua
            // một tài khoản mình kiểm soát.
            throw ApiException.forbidden("Chỉ chủ sở hữu mới thêm được chủ sở hữu");
        }

        Organization organization = load(organizationId);
        if (!organization.isActive()) {
            throw new ApiException(IdentityErrorCode.ORGANIZATION_SUSPENDED, "Organization is suspended");
        }
        UserRecord user = resolve(contact);
        if (!user.isActive()) {
            throw new ApiException(IdentityErrorCode.USER_DISABLED, "Tài khoản này đang bị vô hiệu hoá");
        }

        Membership membership;
        try {
            membership = organization.addMember(user.id(), role, clock.instant());
        } catch (IllegalStateException e) {
            throw new ApiException(IdentityErrorCode.ALREADY_A_MEMBER, "Người này đã là thành viên của tổ chức");
        }
        organizations.save(organization);

        audit.record(
                "MEMBER_ADDED",
                "membership",
                membership.id(),
                null,
                Map.of("userId", user.id().toString(), "role", role.name()));

        // Không mang email: notification-service tự tra địa chỉ theo userId lúc gửi, để dữ liệu cá
        // nhân không nằm rải trong broker (cùng cách với luồng đơn hàng).
        outbox.append(
                CreateOrganizationHandler.EXCHANGE,
                "Membership",
                membership.id(),
                MEMBER_ADDED,
                Map.of(
                        "organizationId", organizationId.value().toString(),
                        "organizationName", organization.name(),
                        "userId", user.id().toString(),
                        "role", role.name()));

        return new Candidate(
                user.id().toString(),
                user.fullName(),
                maskEmail(user.email()),
                user.phone() == null ? null : maskPhone(user.phone()),
                true,
                role.name());
    }

    private UserRecord resolve(String contact) {
        String trimmed = contact == null ? "" : contact.strip();
        if (trimmed.contains("@")) {
            return byEmail(trimmed.toLowerCase(Locale.ROOT));
        }
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (!PHONE_DIGITS.matcher(digits).matches()) {
            throw new ApiException(
                    ErrorCode.Common.VALIDATION_FAILED,
                    "Nhập đúng email hoặc số điện thoại (10 số) của người cần thêm");
        }
        List<UserRecord> matches = users.findByPhone(digits);
        if (matches.size() > 1) {
            throw new ApiException(
                    IdentityErrorCode.CONTACT_AMBIGUOUS,
                    "Có nhiều tài khoản dùng số điện thoại này. Hãy tìm bằng email.");
        }
        return matches.stream().findFirst().orElseThrow(AddMemberHandler::notRegistered);
    }

    private UserRecord byEmail(String email) {
        if (!EMAIL.matcher(email).matches()) {
            throw new ApiException(ErrorCode.Common.VALIDATION_FAILED, "Email không hợp lệ");
        }
        Optional<UserRecord> local = users.findByEmail(email);
        if (local.isPresent()) {
            return local.get();
        }
        try {
            // Đã đăng ký bên Keycloak nhưng chưa từng gọi API nào: dựng bản ghi với đúng `sub` của họ.
            // Tài khoản còn chờ đặt mật khẩu (lời mời nhân viên cũ chưa kích hoạt) thì không tính là
            // "đã đăng ký" — người đó chưa đăng nhập được.
            return identityProvider
                    .findAccountByEmail(email)
                    .filter(IdentityProviderPort.Account::activated)
                    .map(account -> users.upsertByIdpSubject(account.idpSubject(), email, null))
                    .orElseThrow(AddMemberHandler::notRegistered);
        } catch (IdentityProviderPort.IdentityProviderUnavailableException e) {
            // Admin API chưa cấu hình (máy dev) hoặc Keycloak không trả lời. Bảng users không có ai
            // khớp, nên câu trả lời trung thực nhất vẫn là "chưa có tài khoản" — người đó đăng nhập
            // một lần là tìm thấy được.
            log.warn("Không hỏi được Keycloak khi tìm {}: {}", maskEmail(email), e.getMessage());
            throw notRegistered();
        }
    }

    private Organization load(TenantId organizationId) {
        return organizations
                .findById(organizationId)
                .orElseThrow(
                        () -> new ApiException(IdentityErrorCode.ORGANIZATION_NOT_FOUND, "Organization not found"));
    }

    private static ApiException notRegistered() {
        return new ApiException(
                IdentityErrorCode.USER_NOT_REGISTERED,
                "Người này chưa có tài khoản NexaTicket. Hãy nhờ họ đăng ký trước rồi thêm lại.");
    }

    /** {@code nguyenvan@gmail.com} → {@code ng*******@gmail.com}. Tên miền giữ nguyên để dễ nhận ra. */
    static String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        int keep = Math.min(2, local.length() - 1);
        return local.substring(0, Math.max(keep, 1))
                + "*".repeat(Math.max(local.length() - Math.max(keep, 1), 3))
                + email.substring(at);
    }

    /** {@code 0901234567} → {@code 090****567}. */
    static String maskPhone(String phone) {
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() < 7) {
            return "*".repeat(digits.length());
        }
        return digits.substring(0, 3) + "*".repeat(digits.length() - 6) + digits.substring(digits.length() - 3);
    }
}
