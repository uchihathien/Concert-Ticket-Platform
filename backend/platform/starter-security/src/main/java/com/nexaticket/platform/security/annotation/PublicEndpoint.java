// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Endpoint cố ý bỏ qua tenant filter: catalog công khai, webhook SePay.
 *
 * <p>Đánh dấu tường minh để việc review nhìn ra ngay. Mọi endpoint không có annotation này mà truy
 * cập dữ liệu org-owned đều phải đi qua tenant filter.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicEndpoint {
    String reason();
}
