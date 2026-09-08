// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Một container PostgreSQL cho mỗi database, sống suốt vòng đời JVM test.
 *
 * <h2>Vì sao không dùng {@code @Testcontainers} + {@code @Container}</h2>
 *
 * <p>Cặp annotation đó gắn vòng đời container vào <b>lớp test</b>: JUnit gọi {@code stop()} ở
 * {@code afterAll}. Nhưng Spring <b>cache application context</b> theo cấu hình, nên lớp test thứ
 * hai dùng lại đúng context — và đúng Hikari pool — của lớp thứ nhất. Kết quả: lớp đầu xanh, mọi
 * lớp sau đỏ với {@code Connection to localhost:<port> refused} và pool rỗng
 * ({@code total=0, active=0, idle=0}), vì port đó đã chết theo container.
 *
 * <p>{@code withReuse(true)} không cứu được: nó chỉ giúp dùng lại container giữa các <i>lần chạy
 * build</i>, không ngăn JUnit dừng container giữa các lớp test.
 *
 * <p>Nên ở đây container được start một lần trong {@link #forDatabase} rồi <b>không bao giờ dừng</b>
 * — đúng singleton pattern mà Testcontainers khuyến nghị cho lớp base dùng chung. Dọn dẹp là việc
 * của Ryuk (hoặc của lần build sau, khi bật reuse).
 *
 * <h2>Vì sao mỗi database một container</h2>
 *
 * <p>Mỗi service là một bounded context với bộ migration Flyway riêng. Dùng chung một database thì
 * migration của service này sẽ chạy đè lên schema của service kia.
 */
public final class PostgresSingleton {

    /** Ghim tag: đổi phiên bản PostgreSQL là chuyện phải cân nhắc, không phải chuyện trôi theo latest. */
    private static final String IMAGE = "postgres:16-alpine";

    private static final Map<String, PostgreSQLContainer<?>> CONTAINERS = new ConcurrentHashMap<>();

    private PostgresSingleton() {}

    /**
     * Container cho một database, tạo và start ở lần gọi đầu tiên.
     *
     * @param database tên database, ví dụ {@code ledger_db}; cũng dùng làm user và password
     */
    @SuppressWarnings("resource") // Cố ý: container sống hết JVM, đóng nó là hỏng các lớp test sau.
    public static PostgreSQLContainer<?> forDatabase(String database) {
        return CONTAINERS.computeIfAbsent(database, name -> {
            String user = name.endsWith("_db") ? name.substring(0, name.length() - 3) : name;
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>(IMAGE)
                    .withDatabaseName(name)
                    .withUsername(user)
                    .withPassword(user)
                    .withReuse(true);
            try {
                container.start();
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "Không start được PostgreSQL cho database '" + name
                                + "'. Integration test cần Docker đang chạy — kiểm tra `docker info`.",
                        e);
            }
            return container;
        });
    }

    /** Trỏ datasource của Spring vào container. Gọi từ {@code @DynamicPropertySource}. */
    public static void bind(DynamicPropertyRegistry registry, PostgreSQLContainer<?> container) {
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }
}
