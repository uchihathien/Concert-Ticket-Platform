// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.annotation;

import com.nexaticket.kernel.access.Permission;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Quyền cần có để gọi endpoint này.
 *
 * <pre>{@code
 * @DeleteMapping("/organizations/{organizationId}/members/{userId}")
 * @RequiresPermission(Permission.ORG_MEMBERS_MANAGE)
 * public void remove(@PathVariable UUID organizationId, @PathVariable UUID userId) { ... }
 * }</pre>
 *
 * <h2>Đây là lớp thứ hai, không phải lớp duy nhất</h2>
 *
 * <p>Kiểm ở tầng web là <b>bổ sung</b> cho việc kiểm trong handler, không thay thế nó. Lý do đã có
 * sẵn trong codebase này: nhiều lệnh còn được gọi từ chỗ khác ngoài HTTP — bộ dựng dữ liệu mẫu,
 * consumer của message — và một cửa quyền chỉ đóng ở tầng web là một cửa quyền đi vòng được.
 *
 * <p>Giá trị của nó nằm ở chỗ khác: nó làm quyền của một endpoint <b>đọc được ngay tại endpoint
 * đó</b>. Trước đây muốn biết ai gọi được {@code DELETE /members/{id}} thì phải lần vào handler,
 * tìm một hàm private tên {@code requireOrgAdmin}, rồi đọc thân hàm. Với annotation thì câu trả lời
 * nằm ngay trên chữ ký, và một endpoint mới quên khai quyền là một dòng thiếu người review thấy
 * được.
 *
 * <h2>Tổ chức lấy từ đâu</h2>
 *
 * <p>Từ đoạn {@code /organizations/{uuid}} trên đường dẫn — đúng nguồn mà {@code TenantFilter}
 * dùng, và cố ý <b>không</b> lấy từ body hay query (ADR-0008). Quyền {@code PLATFORM_*} thì không
 * cần tổ chức nào.
 *
 * <p>Khai một quyền phạm vi tổ chức trên một route không có {@code /organizations/{id}} sẽ bị từ
 * chối bằng 403 kèm lời giải thích, chứ không âm thầm cho qua.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresPermission {

    Permission value();
}
