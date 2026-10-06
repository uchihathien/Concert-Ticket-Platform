// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

/**
 * Từ chối khởi động khi profile {@code prod} đang bật mà cấu hình vẫn là cấu hình dev.
 *
 * <h2>Vì sao là một chốt chặn thay vì mười một file cấu hình</h2>
 *
 * <p>Cách thường làm là thêm {@code application-prod.yml} cho từng service và đặt lại các giá trị
 * nguy hiểm ở đó. Mười một file nghĩa là mười một chỗ để quên một dòng, và dòng bị quên sẽ không
 * gây ra lỗi nào — nó chỉ lặng lẽ để hệ thống chạy với cấu hình dev ở môi trường thật.
 *
 * <p>Lớp này đảo chiều: mặc định dev vẫn tiện như cũ, còn môi trường thật thì <b>không khởi động
 * nổi</b> cho tới khi mọi giá trị nguy hiểm được thay. Quên một biến môi trường trở thành một lỗi
 * lúc triển khai — đắt đúng một lần, và đắt trước mặt người đang triển khai.
 *
 * <h2>Ba đường leo thang đặc quyền mà nó đóng</h2>
 *
 * <ol>
 *   <li><b>{@code /internal/**} không có bí mật.</b> {@code InternalApiFilter} chỉ được cắm vào khi
 *       {@code nexaticket.internal.shared-secret} có giá trị. Để trống thì
 *       {@code /internal/memberships} tạo được người dùng với email tuỳ ý, và
 *       {@code SuperAdminBootstrap} cấp {@code SUPER_ADMIN} theo email. Ghép hai thứ đó lại: ai
 *       đứng được trong mạng nội bộ tự cấp superadmin cho mình.
 *   <li><b>Danh sách superadmin còn giá trị mặc định.</b> Realm production mới không có tài khoản
 *       nào giữ {@code superadmin@nexaticket.local}, nên địa chỉ đó còn trống — và realm cho phép
 *       tự đăng ký. Người đầu tiên đăng ký email ấy trở thành superadmin của cả nền tảng.
 *   <li><b>Bí mật của Keycloak Admin API còn là bản trong repo.</b> Nó cho phép quản lý mọi tài
 *       khoản trong realm.
 * </ol>
 *
 * <h2>Và một đường làm bẩn dữ liệu</h2>
 *
 * <p>Cờ dữ liệu mẫu bật mặc định ở ba service. Ở production chúng sinh tổ chức, sự kiện và ghế "đã
 * bán" không có thật — lẫn vào dữ liệu thật và không có cách nào phân biệt về sau.
 *
 * <p>Quét theo <b>hình dạng khoá</b> ({@code *.demo-data} và mọi khoá dưới nhánh {@code *.demo.})
 * chứ không theo danh sách liệt kê tay: service thứ tư thêm một cờ mẫu sẽ tự động nằm trong tầm
 * kiểm, thay vì chờ ai đó nhớ ra phải cập nhật danh sách ở đây.
 *
 * <p>Trước đây phép quét chỉ khớp {@code *.demo.enabled}, và điều đó đã bỏ lọt thật:
 * {@code nexaticket.inventory.demo.occupancy} — cờ đánh dấu "đã bán" một phần chỗ của các suất
 * diễn mẫu — bật mặc định và đi qua chốt chặn này mà không ai thấy. Đúng cái mà một danh sách
 * liệt kê tay sinh ra để tránh, nhưng lại tái hiện dưới dạng một khuôn mẫu quá hẹp.
 *
 * <p>Khoá dưới {@code *.demo.} mà KHÔNG phải boolean (id tổ chức mẫu, danh sách email) tự lọt
 * qua: {@code Boolean.parseBoolean} trả {@code false} cho chúng. Nên nới rộng khuôn mẫu không kéo
 * theo báo động giả.
 */
public class ProductionHardening implements EnvironmentPostProcessor, Ordered {

    private static final String PROD_PROFILE = "prod";

    private static final Logger log = LoggerFactory.getLogger(ProductionHardening.class);

    /** Giá trị nằm sẵn trong repo — có mặt ở môi trường thật nghĩa là chưa ai thay. */
    private static final Set<String> DEV_SECRETS = Set.of(
            "dev-secret-identity-admin", "dev-secret-admin", "dev-secret-customer", "doi-gia-tri-nay-o-moi-moi-truong");

    private static final String DEV_SUPER_ADMIN = "superadmin@nexaticket.local";

    /**
     * Chạy ở {@code EnvironmentPostProcessor}, KHÔNG phải ở một bean.
     *
     * <p>Bản đầu là một bean {@code @AutoConfiguration @Profile("prod")}, và nó không có tác dụng:
     * thứ tự tạo bean không đảm bảo nó đi trước, nên Flyway mở kết nối database TRƯỚC và service
     * chết vì "Connection refused". Người triển khai nhận một stack trace về database, đi sửa
     * database, và không bao giờ nhìn thấy câu "bí mật /internal đang trống".
     *
     * <p>{@code EnvironmentPostProcessor} chạy ngay sau khi Environment sẵn sàng và trước bean đầu
     * tiên. {@code LOWEST_PRECEDENCE} để nó đi SAU bộ nạp config data — trước đó thì
     * {@code application.yml} chưa được đọc và mọi thuộc tính đều trống, tức là báo động giả cho
     * mọi lần khởi động.
     */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!List.of(environment.getActiveProfiles()).contains(PROD_PROFILE)) {
            return;
        }
        List<String> problems = inspect(environment);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(message(problems));
        }
        log.info("Kiểm tra cấu hình production: đạt");
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /** Tách khỏi constructor để test gọi được mà không phải dựng cả ApplicationContext. */
    public static List<String> inspect(Environment environment) {
        List<String> problems = new ArrayList<>();

        if (isBlank(environment.getProperty("nexaticket.internal.shared-secret"))) {
            problems.add("nexaticket.internal.shared-secret đang trống: /internal/** mở cho bất kỳ ai "
                    + "trong mạng nội bộ, và /internal/memberships cấp được SUPER_ADMIN theo email");
        }

        String superAdmins = environment.getProperty("nexaticket.identity.super-admin-emails", "");
        if (superAdmins.contains(DEV_SUPER_ADMIN)) {
            problems.add("nexaticket.identity.super-admin-emails vẫn chứa " + DEV_SUPER_ADMIN
                    + ": realm mới chưa ai giữ địa chỉ đó, nên người đầu tiên đăng ký nó thành superadmin");
        }

        String adminSecret = environment.getProperty("nexaticket.identity.keycloak.client-secret", "");
        if (DEV_SECRETS.contains(adminSecret)) {
            problems.add("nexaticket.identity.keycloak.client-secret vẫn là giá trị dev nằm trong repo");
        }

        String issuer = environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "");
        boolean plaintextAllowed =
                Boolean.parseBoolean(environment.getProperty("nexaticket.security.allow-plaintext-issuer", "false"));
        if (!issuer.isEmpty() && issuer.startsWith("http://") && !isLoopback(issuer) && !plaintextAllowed) {
            problems.add("issuer-uri đang là http:// (" + issuer + "): token đi qua mạng không mã hoá. "
                    + "Nếu Keycloak nằm trong cùng cụm và ranh giới TLS ở ingress, khai "
                    + "nexaticket.security.allow-plaintext-issuer=true để nói rõ đó là chủ đích");
        }

        for (String key : demoFlags(environment)) {
            if (Boolean.parseBoolean(environment.getProperty(key, "false"))) {
                problems.add(key + " đang bật: dữ liệu mẫu sẽ lẫn vào dữ liệu thật và không tách ra được");
            }
        }

        return problems;
    }

    /**
     * Mọi khoá trông như một cờ dữ liệu mẫu, gom từ các nguồn cấu hình liệt kê được.
     *
     * <p>Nguồn không liệt kê được (ví dụ bản đọc từ hệ thống bên ngoài) bị bỏ qua — đó là giới hạn
     * thật của cách quét này, và nó hướng về phía an toàn theo nghĩa "kiểm sót" chứ không "chặn
     * nhầm". Ba cờ hiện có đều nằm trong {@code application.yml}, tức là nguồn liệt kê được.
     */
    private static Set<String> demoFlags(Environment environment) {
        Set<String> keys = new LinkedHashSet<>();
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return keys;
        }
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String key : enumerable.getPropertyNames()) {
                    if (key.endsWith(".demo-data") || key.contains(".demo.")) {
                        keys.add(key);
                    }
                }
            }
        }
        return keys;
    }

    /**
     * Thông báo liệt kê <b>tất cả</b> vấn đề, không dừng ở cái đầu tiên.
     *
     * <p>Sửa một lỗi rồi khởi động lại để lộ ra lỗi tiếp theo là ba vòng triển khai cho một việc
     * đáng ra làm một lần.
     */
    private static String message(List<String> problems) {
        StringBuilder text = new StringBuilder("Cấu hình không đạt cho profile `prod` — từ chối khởi động:\n");
        for (String problem : problems) {
            text.append("  • ").append(problem).append('\n');
        }
        text.append("Sửa biến môi trường tương ứng rồi khởi động lại. "
                + "Chi tiết: docs/architecture-v2/plan/production-checklist.md");
        return text.toString();
    }

    /**
     * Loopback luôn chấp nhận; địa chỉ nội bộ khác thì phải khai tường minh.
     *
     * <p>Trong một cụm, service gọi Keycloak qua {@code http://keycloak:8081} là chuyện bình thường
     * — TLS kết thúc ở ingress và lưu lượng bên trong đi trên mạng riêng. Chặn cứng trường hợp đó
     * sẽ khiến người vận hành tắt cả chốt chặn này, và khi đó nó không còn bảo vệ gì nữa.
     *
     * <p>Nên có một khoá bật riêng, {@code nexaticket.security.allow-plaintext-issuer}. Nó không dễ
     * dãi hơn: khác biệt nằm ở chỗ quyết định "chấp nhận token đi trên kênh không mã hoá" được viết
     * ra thành một dòng cấu hình có tên nói đúng việc nó làm — đọc lại sáu tháng sau vẫn hiểu, và
     * hiện ra trong mọi lần rà soát.
     */
    private static boolean isLoopback(String url) {
        return url.contains("://localhost") || url.contains("://127.0.0.1");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
