// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain.port;

import com.nexaticket.kernel.id.UserId;
import java.util.Optional;

public interface UserRepository {

    /**
     * Bản ghi người dùng — nhẹ, không phải aggregate root riêng ở MVP.
     *
     * @param email đến từ IdP và KHÔNG sửa được ở đây: nó là khoá duy nhất, và cho đổi ở phía ta sẽ
     *     làm lệch với Keycloak — nguồn chân lý của danh tính. Đổi email là việc làm ở Keycloak.
     */
    record UserRecord(UserId id, String idpSubject, String email, String fullName, String phone, boolean superAdmin) {}

    Optional<UserRecord> findByIdpSubject(String idpSubject);

    Optional<UserRecord> findByEmail(String email);

    Optional<UserRecord> findById(UserId id);

    /** Tạo nếu chưa có, dùng ở lần request đầu tiên sau khi đăng nhập OIDC. */
    UserRecord upsertByIdpSubject(String idpSubject, String email, String fullName);

    /** Người dùng tự sửa hồ sơ của mình. {@code null} nghĩa là giữ nguyên, không phải xoá. */
    void updateProfile(UserId id, String fullName, String phone);

    /** Tra nhiều người một lần — bảng thành viên cần email và tên, không chỉ id. */
    java.util.List<UserRecord> findAllByIds(java.util.Collection<UserId> ids);
}
