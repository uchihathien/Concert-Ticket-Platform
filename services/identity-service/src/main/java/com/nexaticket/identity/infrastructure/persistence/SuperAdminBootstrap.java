// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.persistence;

import com.nexaticket.identity.domain.port.UserRepository;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Cấp quyền {@code SUPER_ADMIN} theo danh sách email trong cấu hình.
 *
 * <h2>Vì sao cần</h2>
 *
 * <p>Trước đây {@code is_super_admin} chỉ được ĐỌC, không có đường nào ghi: không endpoint, không
 * seed, không migration. Cột luôn {@code false}, nên {@code POST /v1/platform/organizations} —
 * cửa vào duy nhất của toàn bộ luồng onboarding — không bao giờ gọi được. Hệ thống không thể khởi
 * động từ trạng thái rỗng.
 *
 * <h2>Vì sao là cấu hình chứ không phải endpoint</h2>
 *
 * <p>Một endpoint "cấp quyền superadmin" sẽ phải tự bảo vệ bằng chính quyền nó cấp — vòng luẩn
 * quẩn. Danh sách trong biến môi trường thì do người vận hành hạ tầng kiểm soát, cùng nơi họ giữ
 * mật khẩu database, và không cần thêm cơ chế xác thực nào.
 *
 * <p>Cấp một chiều: có tên trong danh sách thì được cấp, nhưng <b>bỏ tên khỏi danh sách không thu
 * hồi quyền</b>. Thu hồi là thao tác cần dấu vết kiểm toán và phải cân nhắc, không phải hệ quả âm
 * thầm của việc sửa một biến môi trường.
 */
@Component
public class SuperAdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminBootstrap.class);

    private final JdbcTemplate jdbc;
    private final Set<String> emails;

    public SuperAdminBootstrap(
            JdbcTemplate jdbc, @Value("${nexaticket.identity.super-admin-emails:}") String configured) {
        this.jdbc = jdbc;
        this.emails = new HashSet<>();
        for (String email : Arrays.asList(configured.split(","))) {
            String trimmed = email.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                emails.add(trimmed);
            }
        }
        if (!emails.isEmpty()) {
            log.info("Cấu hình {} email được cấp quyền superadmin", emails.size());
        }
    }

    /** @return true nếu lần gọi này vừa cấp quyền */
    public boolean grantIfListed(UserRepository.UserRecord user) {
        if (user.superAdmin() || user.email() == null) {
            return false;
        }
        if (!emails.contains(user.email().toLowerCase(Locale.ROOT))) {
            return false;
        }
        jdbc.update(
                "UPDATE users SET is_super_admin = TRUE WHERE id = ?", user.id().value());
        log.warn("Đã cấp quyền SUPER_ADMIN cho {} theo cấu hình", user.id());
        return true;
    }
}
