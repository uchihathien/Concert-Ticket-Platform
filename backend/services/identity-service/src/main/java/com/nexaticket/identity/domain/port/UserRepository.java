// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain.port;

import com.nexaticket.identity.domain.model.UserStatus;
import com.nexaticket.kernel.id.UserId;
import java.util.Optional;

public interface UserRepository {

    /**
     * Bản ghi người dùng — nhẹ, không phải aggregate root riêng ở MVP.
     *
     * @param email đến từ IdP và KHÔNG sửa được ở đây: nó là khoá duy nhất, và cho đổi ở phía ta sẽ
     *     làm lệch với Keycloak — nguồn chân lý của danh tính. Đổi email là việc làm ở Keycloak.
     */
    record UserRecord(
            UserId id,
            String idpSubject,
            String email,
            String fullName,
            String phone,
            boolean superAdmin,
            UserStatus status,
            java.time.Instant tokensValidFrom) {

        /** Tài khoản còn dùng được không. */
        public boolean isActive() {
            return status == UserStatus.ACTIVE;
        }

        /**
         * Token phát ra lúc {@code issuedAt} có còn hiệu lực không.
         *
         * <p>Thiếu {@code issuedAt} thì coi như CÒN hiệu lực: người gọi không biết token phát lúc
         * nào (IdP không phát claim {@code iat}, hoặc lời gọi không đi từ HTTP), và từ chối trong
         * trường hợp đó sẽ khoá cả những người chưa từng bị thu hồi gì.
         */
        public boolean tokenStillValid(java.time.Instant issuedAt) {
            return tokensValidFrom == null || issuedAt == null || !issuedAt.isBefore(tokensValidFrom);
        }
    }

    Optional<UserRecord> findByIdpSubject(String idpSubject);

    Optional<UserRecord> findByEmail(String email);

    Optional<UserRecord> findById(UserId id);

    /** Tạo nếu chưa có, dùng ở lần request đầu tiên sau khi đăng nhập OIDC. */
    UserRecord upsertByIdpSubject(String idpSubject, String email, String fullName);

    /** Người dùng tự sửa hồ sơ của mình. {@code null} nghĩa là giữ nguyên, không phải xoá. */
    void updateProfile(UserId id, String fullName, String phone);

    /** Tra nhiều người một lần — bảng thành viên cần email và tên, không chỉ id. */
    java.util.List<UserRecord> findAllByIds(java.util.Collection<UserId> ids);

    /**
     * Bật/tắt tài khoản.
     *
     * <p>Không xoá bản ghi: đơn hàng, vé đã mua và nhật ký kiểm toán ở năm service khác đều trỏ vào
     * id này.
     */
    void setStatus(UserId id, UserStatus status);

    /**
     * Đặt mốc "mọi token phát trước thời điểm này đều vô hiệu".
     *
     * <p>Một cột trên hàng người dùng thay vì một danh sách token: hàng đó vốn đã được đọc ở mọi
     * request, nên việc thu hồi không thêm lần đi database nào.
     */
    void revokeTokensIssuedBefore(UserId id, java.time.Instant cutoff);
}
