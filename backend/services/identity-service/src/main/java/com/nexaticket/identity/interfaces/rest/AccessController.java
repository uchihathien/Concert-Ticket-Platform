// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.interfaces.rest;

import com.nexaticket.identity.application.query.AccessQueries;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ma trận phân quyền, đọc được từ frontend.
 *
 * <p>Hai endpoint trả lời hai câu khác nhau: {@code /v1/roles} là "hệ thống có những vai trò nào và
 * mỗi vai trò làm được gì" — dữ liệu tĩnh, dùng cho màn hình phân quyền nhân viên. {@code
 * /v1/me/permissions} là "tôi làm được gì, ở tổ chức nào" — dùng để ẩn hiện nút.
 *
 * <p>Cả hai đều đòi đăng nhập. Ma trận không phải bí mật, nhưng nó mô tả bề mặt quản trị của hệ
 * thống, và không có lý do gì để người chưa đăng nhập đọc được nó.
 *
 * <p><b>Không phải cửa quyền.</b> Frontend dùng những con số này để vẽ giao diện; việc chặn thật
 * nằm ở {@code TenantContext.requirePermission} phía server và vẫn chạy dù frontend hỏi hay không.
 */
@RestController
@RequestMapping("/v1")
public class AccessController {

    private final AccessQueries queries;

    public AccessController(AccessQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/roles")
    public List<AccessQueries.RoleView> roles() {
        return queries.allRoles();
    }

    /**
     * Đặt dưới {@code /v1/me/} nên không có tham số id ở bất kỳ đâu — cùng nguyên tắc với
     * {@code MeController}: danh tính lấy từ token, nên endpoint này không thể dùng để dò quyền của
     * người khác.
     */
    @GetMapping("/me/permissions")
    public AccessQueries.MyPermissions myPermissions() {
        return queries.myPermissions();
    }
}
