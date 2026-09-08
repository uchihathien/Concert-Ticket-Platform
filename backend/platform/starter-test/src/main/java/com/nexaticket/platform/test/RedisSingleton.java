// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.test;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;

/**
 * Một container Redis dùng chung cho cả JVM test.
 *
 * <p>Cùng lý do với {@link PostgresSingleton}: vòng đời do lớp này giữ, không do JUnit — xem javadoc
 * ở đó để biết vì sao {@code @Testcontainers} + {@code @Container} làm hỏng lớp test thứ hai.
 *
 * <p>Khác {@code PostgresSingleton} ở chỗ chỉ có một instance chứ không phải một cho mỗi database:
 * Redis ở đây chỉ giữ khoá giữ chỗ tạm thời, không có schema nào để lẫn giữa các service.
 */
public final class RedisSingleton {

    private static final String IMAGE = "redis:7-alpine";
    private static final int PORT = 6379;

    private static final AtomicReference<GenericContainer<?>> INSTANCE = new AtomicReference<>();

    private RedisSingleton() {}

    @SuppressWarnings("resource") // Cố ý: container sống hết JVM, đóng nó là hỏng các lớp test sau.
    public static GenericContainer<?> get() {
        GenericContainer<?> existing = INSTANCE.get();
        if (existing != null) {
            return existing;
        }
        GenericContainer<?> container =
                new GenericContainer<>(IMAGE).withExposedPorts(PORT).withReuse(true);
        if (!INSTANCE.compareAndSet(null, container)) {
            return INSTANCE.get();
        }
        try {
            container.start();
        } catch (RuntimeException e) {
            INSTANCE.set(null);
            throw new IllegalStateException(
                    "Không start được Redis. Integration test cần Docker đang chạy — kiểm tra `docker info`.", e);
        }
        return container;
    }

    /** Trỏ spring.data.redis vào container. Gọi từ {@code @DynamicPropertySource}. */
    public static void bind(DynamicPropertyRegistry registry) {
        GenericContainer<?> container = get();
        registry.add("spring.data.redis.host", container::getHost);
        registry.add("spring.data.redis.port", () -> container.getMappedPort(PORT));
    }
}
