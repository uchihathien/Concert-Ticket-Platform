// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.PropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Chốt chặn cấu hình production.
 *
 * <p>Không dựng ApplicationContext: {@link ProductionHardening#inspect} nhận thẳng
 * {@code Environment}, nên mỗi ca ở đây là một bộ biến môi trường cụ thể và chạy trong mili giây.
 * Đó là điều kiện để bộ test này thật sự được chạy lại mỗi lần ai đó thêm một cờ mới.
 */
class ProductionHardeningTest {

    /** Cấu hình production đúng — mốc so sánh cho mọi ca bên dưới. */
    private static MockEnvironment safe() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("nexaticket.internal.shared-secret", "bi-mat-that-tu-secret-manager");
        env.setProperty("nexaticket.identity.super-admin-emails", "van.hanh@congty-that.vn");
        env.setProperty("nexaticket.identity.keycloak.client-secret", "bi-mat-that");
        env.setProperty(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://id.nexaticket.vn/realms/nexaticket");
        return env;
    }

    @Test
    @DisplayName("cấu hình đúng thì khởi động bình thường")
    void cau_hinh_dung_thi_qua() {
        assertThat(ProductionHardening.inspect(safe())).isEmpty();
    }

    @Test
    @DisplayName("thiếu bí mật /internal: chặn, vì đó là đường tự cấp SUPER_ADMIN")
    void thieu_bi_mat_internal() {
        // InternalApiFilter chỉ được cắm vào khi khoá này có giá trị. Để trống thì
        // /internal/memberships tạo user với email tuỳ ý, và SuperAdminBootstrap cấp quyền theo
        // email — hai thứ ghép lại thành một đường leo thang hoàn chỉnh.
        MockEnvironment env = safe();
        env.setProperty("nexaticket.internal.shared-secret", "");

        assertThat(ProductionHardening.inspect(env)).singleElement().asString().contains("shared-secret");
    }

    @Test
    @DisplayName("còn email superadmin mặc định: chặn")
    void con_email_superadmin_mac_dinh() {
        // Realm production mới không có tài khoản nào giữ địa chỉ đó, và realm cho tự đăng ký:
        // người đầu tiên đăng ký nó thành superadmin của cả nền tảng.
        MockEnvironment env = safe();
        env.setProperty(
                "nexaticket.identity.super-admin-emails", "van.hanh@congty-that.vn,superadmin@nexaticket.local");

        assertThat(ProductionHardening.inspect(env)).singleElement().asString().contains("superadmin@nexaticket.local");
    }

    @Test
    @DisplayName("còn bí mật dev của Keycloak Admin API: chặn")
    void con_bi_mat_dev_cua_keycloak() {
        MockEnvironment env = safe();
        env.setProperty("nexaticket.identity.keycloak.client-secret", "dev-secret-identity-admin");

        assertThat(ProductionHardening.inspect(env)).singleElement().asString().contains("client-secret");
    }

    @Test
    @DisplayName("issuer qua http: chặn — trừ loopback")
    void issuer_phai_la_https() {
        MockEnvironment env = safe();
        env.setProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "http://id.nexaticket.vn/realms/x");
        assertThat(ProductionHardening.inspect(env)).singleElement().asString().contains("http://");

        // Loopback luôn chấp nhận.
        env.setProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "http://localhost:8081/realms/x");
        assertThat(ProductionHardening.inspect(env)).isEmpty();
    }

    @Test
    @DisplayName("http nội bộ trong cụm: chấp nhận, nhưng phải khai tường minh")
    void http_noi_bo_phai_khai_tuong_minh() {
        // Chặn cứng trường hợp này sẽ khiến người vận hành tắt cả chốt chặn — và khi đó nó không
        // bảo vệ gì nữa. Một khoá riêng không dễ dãi hơn: nó biến quyết định thành một dòng cấu
        // hình có tên nói đúng việc nó làm, hiện ra trong mọi lần rà soát.
        MockEnvironment env = safe();
        env.setProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "http://keycloak:8081/realms/x");
        assertThat(ProductionHardening.inspect(env)).hasSize(1);

        env.setProperty("nexaticket.security.allow-plaintext-issuer", "true");
        assertThat(ProductionHardening.inspect(env)).isEmpty();
    }

    @Test
    @DisplayName("cờ dữ liệu mẫu bật: chặn, quét theo hình dạng khoá chứ không theo danh sách")
    void co_du_lieu_mau_bi_chan() {
        // Quét theo hình dạng để service thứ tư thêm một cờ mẫu cũng tự nằm trong tầm kiểm — thay
        // vì chờ ai đó nhớ ra phải cập nhật một danh sách liệt kê tay ở đây.
        MockEnvironment env = safe();
        env.setProperty("nexaticket.catalog.demo-data", "true");
        env.setProperty("nexaticket.identity.demo.enabled", "true");
        env.setProperty("nexaticket.inventory.demo.enabled", "false");
        // Cờ của một service chưa tồn tại: vẫn phải bị bắt.
        env.setProperty("nexaticket.tuong-lai.demo-data", "true");
        // KHÔNG tên là `enabled`, và đã thật sự lọt qua: cờ này bật mặc định, đánh dấu "đã bán"
        // một phần chỗ của các suất diễn mẫu. Khuôn mẫu cũ chỉ khớp `.demo.enabled`.
        env.setProperty("nexaticket.inventory.demo.occupancy", "true");
        // Khoá dưới nhánh `.demo.` nhưng không phải boolean: KHÔNG được báo nhầm.
        env.setProperty("nexaticket.identity.demo.owner-emails", "ai-do@example.com");
        env.setProperty("nexaticket.catalog.demo.organization-id", "00000000-0000-4000-a000-000000000001");

        List<String> problems = ProductionHardening.inspect(env);

        assertThat(problems).hasSize(4);
        assertThat(problems.toString())
                .contains("nexaticket.catalog.demo-data")
                .contains("nexaticket.identity.demo.enabled")
                .contains("nexaticket.tuong-lai.demo-data")
                .contains("nexaticket.inventory.demo.occupancy")
                .doesNotContain("nexaticket.inventory.demo.enabled")
                .doesNotContain("owner-emails")
                .doesNotContain("organization-id");
    }

    @Test
    @DisplayName("liệt kê TẤT CẢ vấn đề trong một lần, không dừng ở cái đầu")
    void liet_ke_tat_ca_van_de() {
        // Sửa một lỗi rồi khởi động lại để lộ ra lỗi tiếp theo là ba vòng triển khai cho một việc.
        MockEnvironment env = new MockEnvironment();
        env.setProperty("nexaticket.identity.super-admin-emails", "superadmin@nexaticket.local");
        env.setProperty("nexaticket.identity.keycloak.client-secret", "dev-secret-identity-admin");
        env.setProperty("nexaticket.catalog.demo-data", "true");

        assertThat(ProductionHardening.inspect(env)).hasSize(4);
    }

    @Test
    @DisplayName("profile prod + cấu hình dev: ném lỗi có nội dung, không phải NPE câm")
    void profile_prod_thi_chan() {
        // Đây là thứ người triển khai đọc lúc 2 giờ sáng. Nó phải nói ra cần sửa gì.
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        assertThatThrownBy(() -> new ProductionHardening().postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("từ chối khởi động")
                .hasMessageContaining("shared-secret");
    }

    @Test
    @DisplayName("không có profile prod thì không kiểm gì — dev vẫn tiện như cũ")
    void ngoai_prod_thi_khong_kiem() {
        // Cả thiết kế dựa vào điều này: mặc định dev giữ nguyên, chỉ môi trường thật mới bị siết.
        // Kiểm cả ở dev thì lập trình viên sẽ tắt nó đi, và tắt rồi thì nó không còn ở đâu cả.
        MockEnvironment env = new MockEnvironment();

        new ProductionHardening().postProcessEnvironment(env, null);
    }

    @Test
    @DisplayName("chạy SAU bộ nạp config data, nếu không mọi thuộc tính đều trống")
    void chay_sau_config_data() {
        // Trước đó `application.yml` chưa được đọc: mọi khoá đều null và chốt chặn báo động giả ở
        // mọi lần khởi động, kể cả khi cấu hình hoàn toàn đúng.
        assertThat(new ProductionHardening().getOrder()).isEqualTo(org.springframework.core.Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    @DisplayName("nguồn cấu hình không liệt kê được thì bị bỏ sót — giới hạn đã biết của cách quét")
    void nguon_khong_liet_ke_duoc_bi_bo_sot() {
        // Ghi lại bằng một ca test để đây là một quyết định đã biết, không phải một bất ngờ: quét
        // theo hình dạng khoá chỉ với tới những nguồn liệt kê được. Ba cờ hiện có đều nằm trong
        // application.yml nên đều trong tầm; một cờ đến từ nguồn ngoài thì không.
        MockEnvironment env = safe();
        env.getPropertySources().addFirst(new PropertySource<Object>("khong-liet-ke-duoc", new Object()) {
            @Override
            public Object getProperty(String name) {
                return "nexaticket.an.demo-data".equals(name) ? "true" : null;
            }
        });

        assertThat(ProductionHardening.inspect(env)).isEmpty();
    }
}
