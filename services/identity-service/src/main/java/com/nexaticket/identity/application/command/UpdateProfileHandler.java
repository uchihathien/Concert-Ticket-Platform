// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.application.command;

import com.nexaticket.identity.domain.port.UserRepository;
import com.nexaticket.platform.security.tenant.TenantContext;
import com.nexaticket.platform.security.tenant.TenantScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Người dùng tự sửa hồ sơ của mình.
 *
 * <p>Chỉ tên hiển thị và số điện thoại. <b>Email không sửa được ở đây</b>, và đó không phải thiếu
 * sót: Keycloak là nguồn chân lý của danh tính, email là khoá duy nhất bên đó và cũng là thứ dùng
 * để khớp lời mời. Cho sửa ở phía ta sẽ tạo ra hai giá trị khác nhau cho cùng một người — và bản ở
 * database của ta là bản sai. Đổi email là việc làm trên Keycloak.
 *
 * <p>Không cần kiểm quyền: mỗi người chỉ sửa được hồ sơ của chính mình, vì id lấy từ
 * {@link TenantScope} chứ không từ đường dẫn. Không có tham số nào để trỏ sang người khác.
 */
@Service
public class UpdateProfileHandler {

    private final UserRepository users;

    public UpdateProfileHandler(UserRepository users) {
        this.users = users;
    }

    @Transactional
    public void handle(String fullName, String phone) {
        TenantScope scope = TenantContext.requireAuthenticated();
        users.updateProfile(scope.userId(), blankToNull(fullName), blankToNull(phone));
    }

    /**
     * Chuỗi rỗng thành null, tức là "giữ nguyên".
     *
     * <p>Form HTML gửi lên chuỗi rỗng cho ô không nhập gì. Không quy đổi thì người dùng chỉ sửa số
     * điện thoại sẽ vô tình xoá tên mình thành chuỗi rỗng.
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
