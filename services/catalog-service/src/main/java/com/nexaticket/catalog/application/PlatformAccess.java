// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application;

import com.nexaticket.platform.security.tenant.TenantContext;
import org.springframework.stereotype.Component;

/**
 * Cửa quyền của khu vực nền tảng trong catalog.
 *
 * <p>Song song với {@link CatalogAccess} và tách khỏi nó có chủ đích: hai cửa trả lời hai câu hỏi
 * khác nhau. {@code CatalogAccess} hỏi "người này quản được sự kiện của tổ chức đó không";
 * ở đây hỏi "người này có phải Tổng công ty không". Gộp làm một sẽ đẻ ra một tham số cờ, và một cờ
 * mặc định sai là mở cửa cho cả nền tảng.
 *
 * <p>Kiểm ở tầng application chứ không ở controller, cùng lý do với {@code CatalogAccess}: lệnh còn
 * được gọi từ chỗ khác ngoài HTTP, và một cửa quyền chỉ đóng ở tầng web là một cửa quyền đi vòng
 * được.
 *
 * <p>{@code TenantFilter} bỏ qua bước lấy tenant cho tiền tố {@code /v1/platform/} — superadmin
 * theo thiết kế không phải thành viên của tổ chức nào — nên ở đây là chốt chặn duy nhất.
 */
@Component
public class PlatformAccess {

    public void requireSuperAdmin() {
        TenantContext.requireSuperAdmin();
    }
}
