// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import com.nexaticket.identity.application.IdentityErrorCode;
import com.nexaticket.identity.domain.model.Membership;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.port.CheckinAssignmentPort;
import com.nexaticket.identity.domain.port.IdentityProviderPort;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.identity.domain.port.StaffAccountRepository;
import com.nexaticket.identity.domain.port.StaffAccountRepository.StaffAccount;
import com.nexaticket.identity.domain.port.StaffAccountRepository.StaffAccountStatus;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.kernel.access.Permission;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.security.tenant.TenantScope;
import com.nexaticket.platform.web.error.ApiException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tài khoản nhân viên soát vé do hệ thống cấp: tên đăng nhập tự sinh + mã PIN 6 số.
 *
 * <h2>Khác lời mời bằng email ở đâu</h2>
 *
 * <p>Cộng tác viên theo ca thường không có email dùng được, và ban tổ chức cần phát tài khoản ngay
 * tại quầy. Nên ở đây hệ thống <b>tự đặt</b> mật khẩu (PIN) — điều mà {@link StaffInvitationHandler}
 * cố ý không làm. Bù lại ba ràng buộc:
 *
 * <ul>
 *   <li>PIN do máy sinh ngẫu nhiên, người bấm nút không chọn được.
 *   <li>PIN chỉ đi ra đúng một lần, trong phản hồi của lệnh tạo / cấp lại. Không lưu ở đâu ngoài
 *       Keycloak (dạng băm), không vào nhật ký kiểm toán, không vào log.
 *   <li>Tài khoản tự hết hạn: khi suất cuối của mọi sự kiện được gán đã kết thúc quá {@code grace},
 *       hệ thống khoá nó bên Keycloak ({@link #expireDue}). Một tài khoản thời vụ không được sống
 *       lâu hơn đêm diễn của nó.
 * </ul>
 *
 * <h2>Tên đăng nhập</h2>
 *
 * <p>{@code <mã tổ chức>-nv<số>}, ví dụ {@code nexa-nv01}. Realm dùng email làm tên đăng nhập, nên
 * bên Keycloak tài khoản là {@code nexa-nv01@staff.nexaticket.invalid} — miền {@code .invalid} dành
 * riêng (RFC 2606), không bao giờ có hộp thư. App nhân viên tự ghép phần sau {@code @}.
 */
@Service
public class StaffAccountHandler {

    private static final Logger log = LoggerFactory.getLogger(StaffAccountHandler.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Số lần thử số thứ tự kế tiếp khi tên đăng nhập đã có người dùng bên Keycloak. */
    private static final int USERNAME_ATTEMPTS = 5;

    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final StaffAccountRepository accounts;
    private final IdentityProviderPort identityProvider;
    private final CheckinAssignmentPort assignments;
    private final AuditLogger audit;
    private final ExternalAuditWriter systemAudit;
    private final Clock clock;
    private final String loginDomain;
    private final Duration grace;
    private final Duration defaultExtension;

    @SuppressWarnings("java:S107") // Bảy cổng + đồng hồ + ba cấu hình; gom lại chỉ đổi chỗ đặt.
    public StaffAccountHandler(
            OrganizationRepository organizations,
            UserRepository users,
            StaffAccountRepository accounts,
            IdentityProviderPort identityProvider,
            CheckinAssignmentPort assignments,
            AuditLogger audit,
            ExternalAuditWriter systemAudit,
            Clock clock,
            @Value("${nexaticket.identity.staff-accounts.login-domain:staff.nexaticket.invalid}") String loginDomain,
            @Value("${nexaticket.identity.staff-accounts.expire-after-last-session:PT6H}") Duration grace,
            @Value("${nexaticket.identity.staff-accounts.default-extension:PT24H}") Duration defaultExtension) {
        this.organizations = organizations;
        this.users = users;
        this.accounts = accounts;
        this.identityProvider = identityProvider;
        this.assignments = assignments;
        this.audit = audit;
        this.systemAudit = systemAudit;
        this.clock = clock;
        this.loginDomain = loginDomain.strip().toLowerCase(Locale.ROOT);
        this.grace = grace;
        this.defaultExtension = defaultExtension;
    }

    /**
     * Thông tin in lên phiếu nhân viên. Chỉ trả về một lần, ngay sau khi tạo hoặc cấp lại PIN.
     *
     * @param username phần nhân viên gõ ({@code nexa-nv01})
     */
    public record Credentials(UUID userId, String fullName, String username, String pin, List<UUID> eventIds) {}

    @Transactional
    public Credentials create(TenantId organizationId, String fullName, String phone, Collection<UUID> eventIds) {
        TenantScope actor = TenantContext.requirePermission(Permission.ORG_MEMBERS_MANAGE, organizationId);
        Organization organization = activeOrganization(organizationId);
        String name = fullName.strip();
        String prefix = prefixFor(organization);
        String pin = newPin();

        String username = null;
        String idpSubject = null;
        for (int attempt = 0; attempt < USERNAME_ATTEMPTS && idpSubject == null; attempt++) {
            username = "%s-nv%02d".formatted(prefix, accounts.takeNextNumber(organizationId));
            try {
                idpSubject = identityProvider.createStaffAccount(loginEmail(username), name, pin);
            } catch (IdentityProviderPort.AccountAlreadyExistsException e) {
                // Có người tạo tay bên Keycloak trùng tên: bỏ số này, lấy số kế tiếp.
                log.warn("Tên đăng nhập {} đã có bên Keycloak, thử số kế tiếp", username);
            } catch (IdentityProviderPort.IdentityProviderUnavailableException e) {
                throw unavailable(e);
            }
        }
        if (idpSubject == null) {
            throw unavailable(new IllegalStateException(
                    "Không tìm được tên đăng nhập còn trống sau " + USERNAME_ATTEMPTS + " lần thử"));
        }

        Instant now = clock.instant();
        try {
            UserRepository.UserRecord user = users.upsertByIdpSubject(idpSubject, loginEmail(username), name);
            if (phone != null && !phone.isBlank()) {
                users.updateProfile(user.id(), null, phone.strip());
            }
            Membership membership = organization.addMember(user.id(), Role.CHECKIN_STAFF, now);
            organizations.save(organization);
            accounts.create(new StaffAccount(
                    user.id(),
                    organizationId,
                    username,
                    StaffAccountStatus.ACTIVE,
                    null,
                    actor.userId(),
                    now,
                    now,
                    now));
            assignments.replace(organizationId, user.id(), eventIds, actor.userId());
            // KHÔNG có PIN trong nhật ký: nhật ký đọc được bởi nhiều người hơn người được phát phiếu.
            audit.record(
                    "STAFF_ACCOUNT_CREATED",
                    "membership",
                    membership.id(),
                    null,
                    Map.of("userId", user.id().toString(), "username", username, "events", eventIds.size()));
            return new Credentials(user.id().value(), name, username, pin, List.copyOf(eventIds));
        } catch (RuntimeException e) {
            // Transaction này rollback, nhưng tài khoản Keycloak thì không tự biến mất — để lại là để
            // lại một tài khoản đăng nhập được mà không ai quản.
            deleteQuietly(idpSubject);
            throw e instanceof CheckinAssignmentPort.AssignmentsUnavailableException unavailable
                    ? unavailable(unavailable)
                    : e;
        }
    }

    /**
     * Cấp PIN mới — khi nhân viên làm mất phiếu, hoặc phiếu lọt vào tay người khác.
     *
     * <p>Đóng luôn mọi phiên đang mở: lý do thường gặp nhất của việc cấp lại là phiếu cũ bị lộ, và
     * PIN mới mà máy của người nhặt được phiếu vẫn đăng nhập thì cấp lại cũng như không.
     */
    @Transactional
    public Credentials resetPin(TenantId organizationId, UserId userId) {
        TenantContext.requirePermission(Permission.ORG_MEMBERS_MANAGE, organizationId);
        StaffAccount account = account(organizationId, userId);
        UserRepository.UserRecord user = user(userId);
        String pin = newPin();
        Instant now = clock.instant();
        try {
            identityProvider.setStaffPin(user.idpSubject(), pin);
            identityProvider.signOutEverywhere(user.idpSubject());
        } catch (IdentityProviderPort.IdentityProviderUnavailableException e) {
            throw unavailable(e);
        }
        users.revokeTokensIssuedBefore(userId, now);
        accounts.markPinIssued(userId, now);
        audit.record("STAFF_PIN_RESET", "user", userId.value(), null, Map.of("username", account.username()));
        return new Credentials(
                userId.value(), user.fullName(), account.username(), pin, eventsOf(organizationId, userId));
    }

    @Transactional
    public void lock(TenantId organizationId, UserId userId) {
        TenantContext.requirePermission(Permission.ORG_MEMBERS_MANAGE, organizationId);
        StaffAccount account = account(organizationId, userId);
        if (account.status() == StaffAccountStatus.LOCKED) {
            return;
        }
        disable(account, StaffAccountStatus.LOCKED);
        audit.record("STAFF_ACCOUNT_LOCKED", "user", userId.value(), null, Map.of("username", account.username()));
    }

    /** Mở khoá một tài khoản bị khoá TAY. Tài khoản hết hạn thì phải gia hạn — xem {@link #extend}. */
    @Transactional
    public void unlock(TenantId organizationId, UserId userId) {
        TenantContext.requirePermission(Permission.ORG_MEMBERS_MANAGE, organizationId);
        StaffAccount account = account(organizationId, userId);
        if (account.status() == StaffAccountStatus.EXPIRED) {
            throw new ApiException(
                    IdentityErrorCode.STAFF_ACCOUNT_EXPIRED, "Tài khoản đã hết hạn. Hãy gia hạn thay vì mở khoá.");
        }
        if (account.status() == StaffAccountStatus.ACTIVE) {
            return;
        }
        enable(account);
        audit.record("STAFF_ACCOUNT_UNLOCKED", "user", userId.value(), null, Map.of("username", account.username()));
    }

    /**
     * Gia hạn: tài khoản dùng được ít nhất tới {@code until} (mặc định 24 giờ nữa), kể cả khi suất
     * cuối đã qua. Tài khoản đang hết hạn được bật lại; tài khoản bị khoá tay thì vẫn khoá.
     */
    @Transactional
    public Instant extend(TenantId organizationId, UserId userId, Instant until) {
        TenantContext.requirePermission(Permission.ORG_MEMBERS_MANAGE, organizationId);
        StaffAccount account = account(organizationId, userId);
        Instant now = clock.instant();
        Instant target = until != null ? until : now.plus(defaultExtension);
        if (!target.isAfter(now)) {
            throw new ApiException(IdentityErrorCode.STAFF_EXTENSION_INVALID, "Hạn mới phải ở tương lai");
        }
        accounts.updateAccessUntil(userId, target);
        if (account.status() == StaffAccountStatus.EXPIRED) {
            enable(account);
        }
        audit.record(
                "STAFF_ACCOUNT_EXTENDED",
                "user",
                userId.value(),
                null,
                Map.of("username", account.username(), "until", target.toString()));
        return target;
    }

    /**
     * Khoá những tài khoản đã hết việc. Chạy định kỳ, không có người dùng nào đứng sau.
     *
     * <p>Hết việc = suất cuối của mọi sự kiện được gán đã kết thúc quá {@code grace}, VÀ đã qua mốc
     * gia hạn tay (nếu có). Tài khoản chưa được gán sự kiện nào thì không có gì để đo, nên để yên —
     * hết hạn nó lúc vừa tạo xong là chặn đúng người ban tổ chức đang chuẩn bị gán việc.
     *
     * @return số tài khoản vừa bị khoá
     */
    @Transactional
    public int expireDue() {
        Instant now = clock.instant();
        int expired = 0;
        Map<TenantId, List<StaffAccount>> byOrganization = new java.util.LinkedHashMap<>();
        for (StaffAccount account : accounts.findActive()) {
            byOrganization
                    .computeIfAbsent(account.organizationId(), key -> new java.util.ArrayList<>())
                    .add(account);
        }
        for (var entry : byOrganization.entrySet()) {
            Map<UserId, Instant> lastEnds;
            try {
                lastEnds = assignments.lastSessionEnds(entry.getKey());
            } catch (CheckinAssignmentPort.AssignmentsUnavailableException e) {
                // Không biết lịch thì KHÔNG khoá: khoá nhầm một nhân viên đang đứng ở cửa tệ hơn
                // nhiều so với để một tài khoản sống thêm một vòng quét.
                log.warn(
                        "Bỏ qua tổ chức {} trong lượt hết hạn tài khoản: {}",
                        entry.getKey().value(),
                        e.getMessage());
                continue;
            }
            for (StaffAccount account : entry.getValue()) {
                Instant lastEnd = lastEnds.get(account.userId());
                if (lastEnd == null) {
                    continue;
                }
                Instant deadline = lastEnd.plus(grace);
                if (account.accessUntil() != null && account.accessUntil().isAfter(deadline)) {
                    deadline = account.accessUntil();
                }
                if (now.isAfter(deadline)) {
                    try {
                        disable(account, StaffAccountStatus.EXPIRED);
                    } catch (ApiException e) {
                        log.warn("Không khoá được tài khoản hết hạn {}: {}", account.username(), e.getMessage());
                        continue;
                    }
                    systemAudit.recordFor(
                            null,
                            account.organizationId().value(),
                            "STAFF_ACCOUNT_EXPIRED",
                            "user",
                            account.userId().value(),
                            Map.of("username", account.username(), "lastSessionEndsAt", lastEnd.toString()));
                    expired++;
                }
            }
        }
        if (expired > 0) {
            log.info("Đã khoá {} tài khoản nhân viên hết hạn", expired);
        }
        return expired;
    }

    /**
     * Trạng thái của một tài khoản PIN theo tên đăng nhập, cho màn đăng nhập của app nhân viên.
     *
     * <p>Không cần đăng nhập — người hỏi chính là người vừa bị Keycloak từ chối vì "Account disabled"
     * và cần biết nên làm gì. Chỉ trả về LOCKED / EXPIRED; mọi trường hợp khác (không có tài khoản,
     * đang dùng được) cùng là UNKNOWN, để đường này không thành chỗ dò xem tên nào đang hoạt động.
     */
    @Transactional(readOnly = true)
    public String publicStatusOf(String username) {
        if (username == null || username.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = username.strip().toLowerCase(Locale.ROOT);
        int at = normalized.indexOf('@');
        if (at >= 0) {
            normalized = normalized.substring(0, at);
        }
        return accounts.findByUsername(normalized)
                .map(StaffAccount::status)
                .filter(status -> status != StaffAccountStatus.ACTIVE)
                .map(Enum::name)
                .orElse("UNKNOWN");
    }

    /** Email nội bộ bên Keycloak của một tên đăng nhập nhân viên. */
    public String loginEmail(String username) {
        return username + "@" + loginDomain;
    }

    private void disable(StaffAccount account, StaffAccountStatus status) {
        UserRepository.UserRecord user = user(account.userId());
        Instant now = clock.instant();
        try {
            identityProvider.setEnabled(user.idpSubject(), false);
            identityProvider.signOutEverywhere(user.idpSubject());
        } catch (IdentityProviderPort.IdentityProviderUnavailableException e) {
            throw unavailable(e);
        }
        // Access token đã phát vẫn còn hạn vài phút; mốc này làm mọi request của chúng bị từ chối
        // ngay ở lượt kiểm danh tính kế tiếp, trên mọi service.
        users.revokeTokensIssuedBefore(account.userId(), now);
        accounts.updateStatus(account.userId(), status, now);
    }

    private void enable(StaffAccount account) {
        try {
            identityProvider.setEnabled(user(account.userId()).idpSubject(), true);
        } catch (IdentityProviderPort.IdentityProviderUnavailableException e) {
            throw unavailable(e);
        }
        accounts.updateStatus(account.userId(), StaffAccountStatus.ACTIVE, clock.instant());
    }

    /**
     * Mã tổ chức của tên đăng nhập: cấp một lần rồi giữ nguyên.
     *
     * <p>Lấy từ slug — phần đầu, tối đa 4 ký tự ({@code nexaticket-demo} → {@code nexa}). Trùng với
     * tổ chức khác thì thêm số ({@code nexa2}); tên đăng nhập phải duy nhất trên cả realm.
     */
    private String prefixFor(Organization organization) {
        Optional<String> existing = accounts.prefixOf(organization.id());
        if (existing.isPresent()) {
            return existing.get();
        }
        String slug = organization.slug().value().toLowerCase(Locale.ROOT);
        String first = slug.split("-")[0].replaceAll("[^a-z0-9]", "");
        String base = first.length() >= 3 ? first : slug.replaceAll("[^a-z0-9]", "");
        base = base.substring(0, Math.min(4, base.length()));
        if (base.length() < 2) {
            base = "nv" + base;
        }
        for (int suffix = 1; suffix < 100; suffix++) {
            String candidate = suffix == 1 ? base : base + suffix;
            if (accounts.reservePrefix(organization.id(), candidate)) {
                return candidate;
            }
            Optional<String> raced = accounts.prefixOf(organization.id());
            if (raced.isPresent()) {
                // Một lệnh tạo khác của chính tổ chức này vừa giữ mã trước.
                return raced.get();
            }
        }
        throw new IllegalStateException(
                "Không cấp được mã đăng nhập cho tổ chức " + organization.id().value());
    }

    /**
     * PIN 6 số ngẫu nhiên, bỏ những dãy ai cũng thử đầu tiên (000000, 123456, 654321…). Chống dò
     * thật sự là việc của Keycloak (khoá tạm sau nhiều lần sai); đây chỉ để PIN không rơi đúng
     * vào danh sách đoán đầu.
     */
    static String newPin() {
        while (true) {
            String pin = "%06d".formatted(RANDOM.nextInt(1_000_000));
            boolean allSame = pin.chars().distinct().count() == 1;
            boolean ascending = "0123456789".contains(pin);
            boolean descending = "9876543210".contains(pin);
            if (!allSame && !ascending && !descending) {
                return pin;
            }
        }
    }

    private List<UUID> eventsOf(TenantId organizationId, UserId userId) {
        try {
            return assignments.eventsOf(organizationId, userId);
        } catch (CheckinAssignmentPort.AssignmentsUnavailableException e) {
            // Phiếu in lại vẫn dùng được khi thiếu danh sách sự kiện — PIN mới là thứ quan trọng.
            return List.of();
        }
    }

    private StaffAccount account(TenantId organizationId, UserId userId) {
        return accounts.find(organizationId, userId)
                .orElseThrow(() -> new ApiException(
                        IdentityErrorCode.STAFF_ACCOUNT_NOT_FOUND, "Không có tài khoản nhân viên này trong tổ chức"));
    }

    private UserRepository.UserRecord user(UserId userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ApiException(IdentityErrorCode.USER_NOT_FOUND, "User not found"));
    }

    private Organization activeOrganization(TenantId organizationId) {
        Organization organization = organizations
                .findById(organizationId)
                .orElseThrow(
                        () -> new ApiException(IdentityErrorCode.ORGANIZATION_NOT_FOUND, "Organization not found"));
        if (!organization.isActive()) {
            throw new ApiException(IdentityErrorCode.ORGANIZATION_SUSPENDED, "Organization is suspended");
        }
        return organization;
    }

    private void deleteQuietly(String idpSubject) {
        try {
            identityProvider.deleteAccount(idpSubject);
        } catch (RuntimeException cleanup) {
            log.error(
                    "Không dọn được tài khoản Keycloak {} sau khi tạo nhân viên hỏng: {}",
                    idpSubject,
                    cleanup.getMessage());
        }
    }

    private static ApiException unavailable(RuntimeException cause) {
        log.warn("Tài khoản nhân viên chưa xử lý được: {}", cause.getMessage(), cause);
        return new ApiException(
                IdentityErrorCode.STAFF_INVITE_UNAVAILABLE, "Chưa xử lý được lúc này. Thử lại sau ít phút.");
    }
}
