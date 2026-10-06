// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger.support;

import com.nexaticket.platform.test.PostgresSingleton;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Nền cho integration test: PostgreSQL thật, Flyway chạy đủ migration của platform và ledger.
 *
 * <p>Bắt buộc phải là database thật, không thể thay bằng H2: cả ba bất biến của sổ cái đều do
 * PostgreSQL ép (constraint trigger DEFERRABLE, partial unique index, CHECK, và quyền role). Test
 * trên database giả sẽ chứng minh một thứ khác với thứ chạy ở production.
 *
 * <p>Vòng đời container do {@link PostgresSingleton} giữ, <b>không</b> do JUnit — xem javadoc ở đó
 * để biết vì sao {@code @Testcontainers} + {@code @Container} làm hỏng lớp test thứ hai.
 *
 * <h2>Test chạy bằng {@code ledger_app}, không bằng owner</h2>
 *
 * <p>Đây là điểm khác biệt quan trọng nhất so với các service khác, và nó có lý do cụ thể.
 *
 * <p>Owner của schema bỏ qua mọi {@code REVOKE} trên bảng của chính mình. Nếu test chạy bằng owner
 * — như trước đây — thì mọi lệnh đều đi qua, và bộ test sẽ xanh với một cấu hình quyền hoàn toàn
 * khác cấu hình chạy thật. Đó chính là cách bất biến "sổ cái chỉ ghi thêm" nằm trong tài liệu suốt
 * thời gian dài mà không hề được ép ở đâu cả.
 *
 * <p>Cho runtime của test chạy đúng role hạn chế còn bắt được một lớp lỗi thứ hai: <b>thiếu một
 * GRANT</b>. Quên cấp quyền trên sequence hay trên {@code ledger_accounts} sẽ làm các IT sẵn có đỏ
 * ngay tại đây, thay vì chỉ hỏng ở môi trường đã tách role — tức là ở production.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class LedgerTestBase {

    private static final PostgreSQLContainer<?> POSTGRES = PostgresSingleton.forDatabase("ledger_db");

    /** Khớp với {@code deploy/compose/initdb/01-databases.sql}. */
    private static final String APP_ROLE = "ledger_app";

    private static final String APP_PASSWORD = "ledger_app";

    static {
        createAppRole();
    }

    /**
     * Tạo role runtime TRƯỚC khi Spring khởi động.
     *
     * <p>Ở dev và production, role này do script khởi tạo database tạo ra. Migration V0101 cố ý
     * <b>ném lỗi</b> nếu không thấy nó, thay vì tự tạo một role không đăng nhập được — một role
     * như vậy sẽ khiến mọi lệnh GRANT chạy trót lọt và trông như đã ép được bất biến, trong khi
     * service vẫn kết nối bằng owner.
     *
     * <p>Nên ở đây test phải dựng đúng tiền đề mà môi trường thật dựng. Idempotent vì container
     * bật {@code withReuse(true)} và sống qua nhiều lần build.
     */
    private static void createAppRole() {
        try (Connection conn = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement st = conn.createStatement()) {
            st.execute(
                    """
                    DO $$
                    BEGIN
                        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ledger_app') THEN
                            CREATE ROLE ledger_app LOGIN PASSWORD 'ledger_app';
                        END IF;
                    END
                    $$;
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("Không tạo được role " + APP_ROLE + " trong container test", e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        // Runtime: role hạn chế, KHÔNG có UPDATE/DELETE trên journal_entries và postings.
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        // Migration: owner của schema. Trong container test, đó là user mà Testcontainers tạo sẵn.
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /** Cho test cần mở kết nối riêng bằng đúng role runtime (xem {@code LedgerAppendOnlyIT}). */
    protected static Connection connectAsApp() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD);
    }
}
