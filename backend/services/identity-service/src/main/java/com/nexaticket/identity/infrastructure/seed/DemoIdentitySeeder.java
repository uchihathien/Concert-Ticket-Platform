// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.seed;

import com.nexaticket.identity.domain.model.Invitation;
import com.nexaticket.identity.domain.model.Organization;
import com.nexaticket.identity.domain.model.OrganizationStatus;
import com.nexaticket.identity.domain.model.Slug;
import com.nexaticket.identity.domain.port.InvitationRepository;
import com.nexaticket.identity.domain.port.OrganizationRepository;
import com.nexaticket.identity.domain.port.PurchaseLimitsRepository;
import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dựng người dùng, tổ chức, thành viên và lời mời mẫu lúc khởi động.
 *
 * <p>Cùng lý do với bộ dựng catalog: một danh sách tổ chức rỗng làm khu vực quản trị và trang nền
 * tảng trông như hỏng. Nhưng ở đây còn một lý do riêng và quan trọng hơn — <b>bảng thành viên là
 * màn hình duy nhất mà bốn vai trò cùng xuất hiện</b>. Không có dữ liệu thật cho cả bốn thì không
 * nhìn được cái mà màn hình đó tồn tại để cho thấy: ai làm được gì.
 *
 * <p><b>Nối được với dữ liệu của Catalog.</b> Một trong các tổ chức mẫu mang đúng id mà
 * catalog-service gán cho 26 sự kiện mẫu ({@code nexaticket.catalog.demo-organization-id}). Nhờ vậy
 * đăng nhập bằng một tài khoản trong danh sách {@code owner-emails} là thấy ngay toàn bộ sự kiện đó
 * trong app tổ chức — thay vì một khu vực quản trị trống trơn nằm cạnh một trang khách đầy sự kiện,
 * và không có gì cho biết hai bên vốn là một.
 *
 * <p><b>Không bao giờ đụng vào dữ liệu có sẵn.</b> Tổ chức nào đã tồn tại thì bỏ qua nguyên cụm,
 * người dùng thì upsert theo {@code idp_subject}. Database này chứa tài khoản thật đến từ Keycloak
 * và tổ chức thật do người dùng tạo — mất chúng là mất đường đăng nhập.
 *
 * <p>Đi thẳng qua repository chứ không qua handler: handler đòi {@code TenantContext}, mà lúc khởi
 * động không có người dùng nào. Đi qua handler cũng sẽ bắn {@code organization.created} lên broker
 * và gửi năm cái email mời cho những địa chỉ không có thật.
 */
@Component
@ConditionalOnProperty(prefix = "nexaticket.identity.demo", name = "enabled", havingValue = "true")
public class DemoIdentitySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoIdentitySeeder.class);

    /**
     * Tiền tố của {@code idp_subject} người dùng mẫu.
     *
     * <p>Keycloak phát subject là UUID, nên tiền tố có dấu hai chấm bảo đảm không bao giờ trùng với
     * một tài khoản thật. Nó cũng là cách phân biệt duy nhất đáng tin: nhìn một dòng trong bảng
     * {@code users} là biết ngay nó đến từ đâu.
     */
    private static final String IDP_PREFIX = "demo:";

    private final OrganizationRepository organizations;
    private final UserRepository users;
    private final InvitationRepository invitations;
    private final PurchaseLimitsRepository purchaseLimits;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final java.time.Clock clock;
    private final UUID catalogOrganizationId;
    private final List<String> ownerEmails;

    public DemoIdentitySeeder(
            OrganizationRepository organizations,
            UserRepository users,
            InvitationRepository invitations,
            PurchaseLimitsRepository purchaseLimits,
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            java.time.Clock clock,
            @Value("${nexaticket.identity.demo.catalog-organization-id}") UUID catalogOrganizationId,
            @Value("${nexaticket.identity.demo.owner-emails:}") String ownerEmails) {
        this.organizations = organizations;
        this.users = users;
        this.invitations = invitations;
        this.purchaseLimits = purchaseLimits;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
        this.catalogOrganizationId = catalogOrganizationId;
        this.ownerEmails = Arrays.stream(ownerEmails.split(","))
                .map(email -> email.trim().toLowerCase(Locale.ROOT))
                .filter(email -> !email.isEmpty())
                .toList();
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, UserId> people = transactions.execute(status -> ensurePeople());
        int created = 0;
        for (DemoIdentitySeeder.OrgSpec spec : DemoIdentityData.ORGANIZATIONS) {
            if (Boolean.TRUE.equals(transactions.execute(status -> ensureOrganization(spec, people)))) {
                created++;
            }
        }
        transactions.executeWithoutResult(status -> attachRealOwners());

        if (created > 0) {
            log.info("Đã dựng {} tổ chức mẫu với {} người dùng mẫu", created, people.size());
        }
    }

    // --- Người dùng ---------------------------------------------------------

    /**
     * Tạo (hoặc tìm lại) người dùng mẫu.
     *
     * <p>Số điện thoại chỉ ghi khi ô đó còn trống: nếu ai đó đã sửa hồ sơ trên giao diện, lần khởi
     * động sau không được ghi đè lên thứ họ vừa nhập.
     */
    private Map<String, UserId> ensurePeople() {
        Map<String, UserId> byKey = new LinkedHashMap<>();
        for (PersonSpec person : DemoIdentityData.PEOPLE) {
            UserRepository.UserRecord record =
                    users.upsertByIdpSubject(IDP_PREFIX + person.key(), person.email(), person.fullName());
            if (record.phone() == null) {
                users.updateProfile(record.id(), null, person.phone());
            }
            byKey.put(person.key(), record.id());
        }
        return byKey;
    }

    // --- Tổ chức ------------------------------------------------------------

    /** @return true nếu lần này vừa tạo mới */
    private boolean ensureOrganization(OrgSpec spec, Map<String, UserId> people) {
        TenantId id = TenantId.of(spec.ownsCatalogEvents() ? catalogOrganizationId : idOf("organization", spec.slug()));
        if (organizations.findById(id).isPresent()) {
            return false;
        }

        Instant createdAt = clock.instant().minus(Duration.ofDays(spec.createdDaysAgo()));
        Organization organization =
                Organization.rehydrate(id, new Slug(spec.slug()), spec.name(), spec.status(), List.of(), createdAt);

        int order = 0;
        for (MemberSpec member : spec.members()) {
            UserId userId = people.get(member.personKey());
            if (userId == null) {
                log.warn("Bỏ qua thành viên mẫu {}: không có người dùng nào mang khoá đó", member.personKey());
                continue;
            }
            // Người vào sau chủ sở hữu vài ngày. Cùng một mốc cho tất cả sẽ khiến bảng thành viên
            // xếp theo ngày tham gia trông như một lần nhập liệu hàng loạt, đúng cái nó là.
            organization.addMember(userId, member.role(), createdAt.plus(Duration.ofDays(order++)));
        }
        organizations.save(organization);

        saveProfile(id.value(), spec.profile());
        if (spec.limits() != null) {
            purchaseLimits.save(id, spec.limits());
        }
        for (InviteSpec invite : spec.invitations()) {
            saveInvitation(id, invite);
        }
        recordAudit(id.value(), createdAt, spec);
        return true;
    }

    /**
     * Gắn tài khoản thật vào tổ chức đang giữ sự kiện mẫu của Catalog.
     *
     * <p>Đây là chỗ dữ liệu mẫu thôi làm cảnh và bắt đầu dùng được: không có bước này thì 26 sự
     * kiện mẫu thuộc về một tổ chức không ai là thành viên, và khu vực quản trị vẫn trống dù
     * database đầy dữ liệu.
     *
     * <p>Chạy tách khỏi {@link #ensureOrganization}: tổ chức đã tồn tại từ lần khởi động trước thì
     * bước kia bỏ qua nguyên cụm, nhưng danh sách email trong cấu hình vẫn có thể vừa được thêm.
     */
    private void attachRealOwners() {
        if (ownerEmails.isEmpty()) {
            return;
        }
        Organization organization =
                organizations.findById(TenantId.of(catalogOrganizationId)).orElse(null);
        if (organization == null) {
            return;
        }
        boolean changed = false;
        for (String email : ownerEmails) {
            UserRepository.UserRecord user = users.findByEmail(email).orElse(null);
            if (user == null) {
                log.info("Chưa có tài khoản {} trong database — sẽ gắn vào tổ chức mẫu sau lần đăng nhập đầu", email);
                continue;
            }
            if (organization.findMember(user.id()).isPresent()) {
                continue;
            }
            organization.addMember(user.id(), Role.ORG_OWNER, clock.instant());
            changed = true;
            log.info("Đã gắn {} làm ORG_OWNER của tổ chức giữ sự kiện mẫu", email);
        }
        if (changed) {
            organizations.save(organization);
        }
    }

    private void saveInvitation(TenantId organizationId, InviteSpec spec) {
        Instant now = clock.instant();
        Invitation invitation =
                switch (spec.state()) {
                        // Lời mời còn hiệu lực: phát token thật rồi vứt bản thô đi. Không ai bấm được
                        // vào nó, và đó là đúng — lời mời mẫu để nhìn danh sách, không phải để nhận.
                    case PENDING -> Invitation.issue(organizationId, spec.email(), spec.role(), now)
                            .invitation();
                        // Hai trạng thái dưới KHÔNG dựng được bằng issue(): hết hạn và đã nhận đều nằm
                        // trong quá khứ, mà issue() luôn phát một lời mời còn bảy ngày hiệu lực.
                    case EXPIRED -> Invitation.rehydrate(
                            idOf("invitation", organizationId.value() + ":" + spec.email()),
                            organizationId,
                            spec.email(),
                            spec.role(),
                            Invitation.hash("demo-expired:" + organizationId.value() + ":" + spec.email()),
                            now.minus(Duration.ofDays(3)),
                            null);
                    case ACCEPTED -> Invitation.rehydrate(
                            idOf("invitation", organizationId.value() + ":" + spec.email()),
                            organizationId,
                            spec.email(),
                            spec.role(),
                            Invitation.hash("demo-accepted:" + organizationId.value() + ":" + spec.email()),
                            now.minus(Duration.ofDays(20)),
                            now.minus(Duration.ofDays(25)));
                };
        invitations.save(invitation);
    }

    /**
     * Hồ sơ pháp nhân.
     *
     * <p>Ghi thẳng bằng SQL vì <b>chưa có repository nào cho bảng này</b> — nó do superadmin nhập
     * lúc tạo tổ chức (custodial-funds.md §8) và màn hình đó chưa làm. Dựng sẵn dữ liệu để khi màn
     * hình ra đời nó có thứ để hiển thị, thay vì phải nhập tay năm tổ chức mới nhìn được một bảng.
     */
    private void saveProfile(UUID organizationId, ProfileSpec profile) {
        jdbc.update(
                """
                INSERT INTO organization_profiles (organization_id, legal_name, tax_code,
                                                   representative_name, contact_email, contact_phone, note)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (organization_id) DO NOTHING
                """,
                organizationId,
                profile.legalName(),
                profile.taxCode(),
                profile.representativeName(),
                profile.contactEmail(),
                profile.contactPhone(),
                profile.note());
    }

    /**
     * Vết kiểm toán của chính việc dựng dữ liệu.
     *
     * <p>Không dùng {@code AuditLogger}: nó lấy người thực hiện từ {@code TenantContext}, mà lúc
     * khởi động không có ai đăng nhập. Ghi thẳng với {@code actor_user_id} rỗng, và đó là sự thật
     * — không người nào tạo mấy tổ chức này.
     */
    private void recordAudit(UUID organizationId, Instant createdAt, OrgSpec spec) {
        insertAudit(
                organizationId, createdAt, "ORGANIZATION_CREATED", Map.of("slug", spec.slug(), "name", spec.name()));
        if (spec.status() == OrganizationStatus.SUSPENDED) {
            insertAudit(
                    organizationId,
                    createdAt.plus(Duration.ofDays(3)),
                    "ORGANIZATION_SUSPENDED",
                    Map.of("reason", "Hồ sơ pháp nhân chưa hợp lệ"));
        }
        if (spec.limits() != null) {
            insertAudit(
                    organizationId,
                    createdAt.plus(Duration.ofDays(1)),
                    "PURCHASE_LIMITS_UPDATED",
                    Map.of("maxTicketsPerCustomer", String.valueOf(spec.limits().maxTicketsPerCustomer())));
        }
    }

    private void insertAudit(UUID organizationId, Instant at, String action, Map<String, String> after) {
        StringBuilder json = new StringBuilder("{");
        for (Map.Entry<String, String> entry : after.entrySet()) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append('"')
                    .append(entry.getKey())
                    .append("\":\"")
                    .append(escape(entry.getValue()))
                    .append('"');
        }
        json.append('}');
        jdbc.update(
                """
                INSERT INTO audit_logs (id, actor_user_id, organization_id, action, entity_type,
                                        entity_id, after_state, created_at)
                VALUES (?, NULL, ?, ?, 'organization', ?, ?::jsonb, ?)
                """,
                UUID.randomUUID(),
                organizationId,
                action,
                organizationId,
                json.toString(),
                Timestamp.from(at));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * UUID suy ra từ tên, không phải ngẫu nhiên — cùng lý do với bộ dựng của Catalog: dựng lại trên
     * database trống cho ra đúng các id cũ, nên link đã lưu và ảnh chụp màn hình vẫn còn khớp.
     */
    private static UUID idOf(String kind, String key) {
        return UUID.nameUUIDFromBytes(("nexaticket:demo:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    // --- Hình dạng dữ liệu mẫu ---------------------------------------------

    record PersonSpec(String key, String fullName, String email, String phone) {}

    record MemberSpec(String personKey, Role role) {}

    /** Ba trạng thái của một lời mời mà bảng "Lời mời đang chờ" phải phân biệt được. */
    enum InviteState {
        PENDING,
        EXPIRED,
        ACCEPTED
    }

    record InviteSpec(String email, Role role, InviteState state) {}

    record ProfileSpec(
            String legalName,
            String taxCode,
            String representativeName,
            String contactEmail,
            String contactPhone,
            String note) {}

    /**
     * Một tổ chức mẫu.
     *
     * @param ownsCatalogEvents tổ chức này mang đúng id mà catalog-service gán cho sự kiện mẫu.
     *     Chỉ MỘT tổ chức được đặt cờ này; đặt ở hai chỗ là hai tổ chức cùng id.
     * @param createdDaysAgo tuổi của tổ chức tính từ lúc chạy, để danh sách xếp theo ngày tạo
     *     không phải là một cột giá trị giống hệt nhau
     * @param limits {@code null} nghĩa là kế thừa trần nền tảng — trạng thái thường gặp nhất và
     *     cũng phải có mẫu
     */
    record OrgSpec(
            String slug,
            String name,
            OrganizationStatus status,
            boolean ownsCatalogEvents,
            int createdDaysAgo,
            ProfileSpec profile,
            PurchaseLimitsRepository.Limits limits,
            List<MemberSpec> members,
            List<InviteSpec> invitations) {}
}
