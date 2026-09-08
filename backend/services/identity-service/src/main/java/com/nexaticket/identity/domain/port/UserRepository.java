// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain.port;

import com.nexaticket.kernel.id.UserId;
import java.util.Optional;

public interface UserRepository {

    /** Bản ghi người dùng — nhẹ, không phải aggregate root riêng ở MVP. */
    record UserRecord(UserId id, String idpSubject, String email, String fullName, boolean superAdmin) {}

    Optional<UserRecord> findByIdpSubject(String idpSubject);

    Optional<UserRecord> findByEmail(String email);

    Optional<UserRecord> findById(UserId id);

    /** Tạo nếu chưa có, dùng ở lần request đầu tiên sau khi đăng nhập OIDC. */
    UserRecord upsertByIdpSubject(String idpSubject, String email, String fullName);
}
