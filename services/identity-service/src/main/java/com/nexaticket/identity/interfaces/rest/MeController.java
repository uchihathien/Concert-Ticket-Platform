// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import com.nexaticket.identity.application.command.UpdateProfileHandler;
import com.nexaticket.identity.application.query.OrganizationQueries;
import com.nexaticket.identity.application.query.ProfileView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Tôi là ai" — endpoint đầu tiên mọi app gọi sau khi đăng nhập.
 *
 * <p>Trước đây chỉ có {@code GET /v1/me/organizations}, nên frontend biết mình thuộc tổ chức nào
 * nhưng không biết tên hay email của chính người đang đăng nhập. Nó phải lấy tạm từ JWT — mà JWT
 * là ảnh chụp lúc đăng nhập, không đổi theo khi người dùng sửa hồ sơ, và không mang những trường
 * chỉ có ở phía ta như số điện thoại hay cờ superadmin.
 *
 * <p>Không có tham số id ở bất kỳ đâu: danh tính lấy từ token. Nhờ vậy endpoint này không thể dùng
 * để đọc hồ sơ người khác, kể cả khi ai đó quên một bước kiểm tra.
 */
@RestController
@RequestMapping("/v1/me")
public class MeController {

    private final OrganizationQueries queries;
    private final UpdateProfileHandler updateProfile;

    public MeController(OrganizationQueries queries, UpdateProfileHandler updateProfile) {
        this.queries = queries;
        this.updateProfile = updateProfile;
    }

    @GetMapping
    public ProfileView me() {
        return queries.currentProfile();
    }

    @PatchMapping
    public ProfileView update(@Valid @RequestBody ProfilePatch request) {
        updateProfile.handle(request.fullName(), request.phone());
        return queries.currentProfile();
    }

    /**
     * Email cố ý vắng mặt: Keycloak là nguồn chân lý của danh tính, và email cũng là khoá khớp lời
     * mời. Sửa ở phía ta sẽ tạo hai giá trị khác nhau cho cùng một người.
     *
     * <p>Số điện thoại nhận khoảng rộng ký tự vì người Việt gõ đủ kiểu — "0912 345 678",
     * "+84912345678". Chuẩn hoá là việc của chỗ nào thật sự gửi SMS, không phải của ô nhập liệu.
     */
    public record ProfilePatch(
            @Size(max = 120) String fullName,
            @Size(max = 24) @Pattern(regexp = "^[0-9+()\\s.-]*$", message = "Số điện thoại không hợp lệ")
                    String phone) {}
}
