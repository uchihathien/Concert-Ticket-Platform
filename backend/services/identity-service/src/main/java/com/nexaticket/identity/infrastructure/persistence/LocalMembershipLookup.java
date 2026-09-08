// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.persistence;

import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.kernel.access.Role;
import com.nexaticket.kernel.id.TenantId;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.MembershipLookup;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * identity-service tự cài {@link MembershipLookup} trực tiếp trên database của nó.
 *
 * <p>Các service khác cài bằng HTTP client gọi {@code /internal/users/provision} có cache ngắn —
 * không service nào đọc bảng {@code organization_members} của identity (ADR-1002).
 */
@Component
public class LocalMembershipLookup implements MembershipLookup {

    private static final Logger log = LoggerFactory.getLogger(LocalMembershipLookup.class);

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final SuperAdminBootstrap superAdmins;

    public LocalMembershipLookup(JdbcTemplate jdbc, UserRepository users, SuperAdminBootstrap superAdmins) {
        this.jdbc = jdbc;
        this.users = users;
        this.superAdmins = superAdmins;
    }

    /**
     * Đọc trước, chỉ ghi khi thật sự là lần đầu.
     *
     * <p>Không dùng UPSERT thẳng cho mọi request: đây là đường nóng chạy trên <b>mọi</b> request đã
     * đăng nhập của toàn hệ thống, và biến nó thành một lệnh ghi sẽ tạo ra lượng WAL vô ích cùng
     * tranh chấp hàng trên chính bảng users.
     */
    @Override
    @Transactional
    public Principal resolve(Claims claims) {
        if (claims == null || claims.subject() == null) {
            return null;
        }

        Optional<UserRepository.UserRecord> existing = users.findByIdpSubject(claims.subject());
        UserRepository.UserRecord user;
        if (existing.isPresent()) {
            user = existing.get();
        } else {
            if (claims.email() == null || claims.email().isBlank()) {
                // Không có email thì không tạo được bản ghi. Trả null để request nhận 401 kèm log
                // rõ ràng, thay vì tạo một người dùng không liên lạc được.
                log.warn("JWT của subject {} không có claim email, không tạo được người dùng", claims.subject());
                return null;
            }
            try {
                user = users.upsertByIdpSubject(claims.subject(), claims.email(), claims.fullName());
            } catch (DuplicateKeyException e) {
                // Email đã thuộc về một `sub` khác.
                //
                // KHÔNG tự chuyển bản ghi cũ sang `sub` mới, dù rất cám dỗ: `sub` là danh tính,
                // email chỉ là thuộc tính. Nhận diện người dùng theo email là đúng cái lỗ hổng
                // chiếm tài khoản kinh điển — ai đổi email ở IdP thành email của người khác sẽ
                // thừa hưởng luôn tài khoản đó, kể cả quyền superadmin.
                //
                // Ở môi trường dev, nguyên nhân gần như luôn là realm Keycloak vừa được import
                // lại: người dùng bị tạo mới với id mới, còn identity_db vẫn giữ id cũ. Cách sửa
                // là dọn bản ghi cũ hoặc trỏ nó sang `sub` mới BẰNG TAY — một thao tác có người
                // chịu trách nhiệm, không phải một nhánh code chạy âm thầm.
                log.error(
                        "Email {} đã gắn với một idp_subject khác; subject mới {} không tạo được bản ghi. "
                                + "Nếu vừa import lại realm Keycloak: cập nhật users.idp_subject sang giá trị mới, "
                                + "hoặc xoá bản ghi cũ.",
                        claims.email(),
                        claims.subject());
                return null;
            }
            log.info("Đã tạo người dùng {} ở lần đăng nhập đầu tiên", user.id());
        }

        boolean superAdmin = user.superAdmin() || superAdmins.grantIfListed(user);
        return new Principal(user.id(), loadMemberships(user.id()), superAdmin);
    }

    private Map<TenantId, Role> loadMemberships(UserId userId) {
        Map<TenantId, Role> result = new HashMap<>();
        jdbc.query(
                "SELECT organization_id, role FROM organization_members WHERE user_id = ?",
                rs -> {
                    result.put(
                            TenantId.of(rs.getObject("organization_id", UUID.class)),
                            Role.valueOf(rs.getString("role")));
                },
                userId.value());
        return result;
    }
}
