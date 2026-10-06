// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Ngoại lệ hẹp với ADR-0008: truy vấn đọc dữ liệu của tổ chức khác.
 *
 * <p>Hiện <b>chỉ một chỗ</b> được phép dùng: phát hiện trùng lịch địa điểm (ADR-1013). Bốn ràng
 * buộc bắt buộc kèm theo:
 *
 * <ol>
 *   <li>Chỉ trả về trường tối thiểu; dữ liệu nhận dạng chỉ ghép vào sau khi kiểm {@code PUBLISHED}.
 *   <li>Ghi audit mỗi lần chạy.
 *   <li>Có integration test khẳng định không rò rỉ khi bản ghi kia chưa công bố.
 *   <li>Thêm chỗ dùng mới phải có ADR.
 * </ol>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CrossTenantQuery {
    String reason();
}
