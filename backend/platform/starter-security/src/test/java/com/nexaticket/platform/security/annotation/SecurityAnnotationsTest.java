// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.security.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PublicEndpoint} và {@link CrossTenantQuery}: hai lối thoát khỏi tenant guard.
 *
 * <p>Cả hai phải đọc được lúc chạy (ArchUnit và người review dựa vào chúng), và cả hai bắt buộc
 * nêu <b>lý do</b> — không có giá trị mặc định cho {@code reason}, nên không ai khai được một
 * ngoại lệ mà không viết ra vì sao.
 */
class SecurityAnnotationsTest {

    @PublicEndpoint(reason = "catalog công khai")
    static class PublicCatalog {
        @CrossTenantQuery(reason = "ADR-1013: phát hiện trùng lịch địa điểm")
        public void overlapping() {}
    }

    @Test
    @DisplayName("@PublicEndpoint giữ tới runtime, đặt được trên lớp và method, đọc được lý do")
    void public_endpoint_doc_duoc_luc_chay() {
        assertThat(PublicEndpoint.class.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(PublicEndpoint.class.getAnnotation(Target.class).value())
                .containsExactlyInAnyOrder(ElementType.METHOD, ElementType.TYPE);
        assertThat(PublicCatalog.class.getAnnotation(PublicEndpoint.class).reason())
                .isEqualTo("catalog công khai");
    }

    @Test
    @DisplayName("@CrossTenantQuery chỉ đặt được trên method — ngoại lệ hẹp, không phủ cả lớp")
    void cross_tenant_query_chi_tren_method() throws Exception {
        assertThat(CrossTenantQuery.class.getAnnotation(Retention.class).value())
                .isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(CrossTenantQuery.class.getAnnotation(Target.class).value()).containsExactly(ElementType.METHOD);

        Method method = PublicCatalog.class.getMethod("overlapping");
        assertThat(method.getAnnotation(CrossTenantQuery.class).reason()).startsWith("ADR-1013");
    }

    @Test
    @DisplayName("reason là bắt buộc: không có giá trị mặc định ở cả hai annotation")
    void reason_bat_buoc() throws Exception {
        assertThat(PublicEndpoint.class.getMethod("reason").getDefaultValue()).isNull();
        assertThat(CrossTenantQuery.class.getMethod("reason").getDefaultValue()).isNull();
        assertThat(RequiresPermission.class.getMethod("value").getDefaultValue())
                .isNull();
    }
}
