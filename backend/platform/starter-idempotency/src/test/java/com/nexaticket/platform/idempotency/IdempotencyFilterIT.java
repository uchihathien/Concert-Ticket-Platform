// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.kernel.id.UserId;
import com.nexaticket.platform.security.tenant.TenantContextFixture;
import com.nexaticket.platform.test.PostgresSingleton;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * {@link IdempotencyFilter} với {@link IdempotencyStore} thật trên PostgreSQL: câu trả lời đi qua
 * cột jsonb và quay lại nguyên vẹn, hai request song song cùng khoá chỉ chạy handler một lần, và bản
 * ghi dở của tiến trình đã chết được nhận lại.
 *
 * <p>Mọi khoá và người dùng sinh ngẫu nhiên mỗi lần chạy (container được dùng lại giữa các lần chạy).
 */
class IdempotencyFilterIT {

    private static JdbcTemplate jdbc;

    private final AtomicInteger handlerRuns = new AtomicInteger();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    @RestController
    class Slow {
        @PostMapping("/v1/orders")
        org.springframework.http.ResponseEntity<String> place(@RequestBody String body) throws InterruptedException {
            int run = handlerRuns.incrementAndGet();
            Thread.sleep(400);
            return org.springframework.http.ResponseEntity.status(201)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"orderId\":\"o-" + run + "\",\"giá\":1500000,\"ghi chú\":\"Vé đứng\"}");
        }
    }

    @BeforeAll
    static void dung_schema() throws Exception {
        PostgreSQLContainer<?> pg = PostgresSingleton.forDatabase("idempotency_it_db");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM pg_tables WHERE tablename = 'idempotency_records'", Integer.class);
        if (exists == null || exists == 0) {
            jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/platform/V0002__idempotency.sql")));
        }
    }

    @AfterEach
    void tearDown() {
        TenantContextFixture.clear();
        pool.shutdownNow();
    }

    private MockMvc mvc(Duration staleAfter) {
        IdempotencyProperties props = new IdempotencyProperties(List.of("/v1/orders"), staleAfter, null);
        return MockMvcBuilders.standaloneSetup(new Slow())
                .addFilters(new IdempotencyFilter(props, Optional.of(new IdempotencyStore(jdbc)), new ObjectMapper()))
                .build();
    }

    @Test
    @DisplayName("Gửi lại cùng khoá: nhận đúng câu trả lời đã lưu (kể cả tiếng Việt), handler chỉ chạy một lần")
    void phat_lai_qua_jsonb() throws Exception {
        UUID user = UUID.randomUUID();
        String key = "k-" + UUID.randomUUID();
        MockMvc mvc = mvc(Duration.ofMinutes(2));
        TenantContextFixture.customer(UserId.of(user));

        String first = mvc.perform(post("/v1/orders")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdId\":\"a\"}"))
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var replay = mvc.perform(post("/v1/orders")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdId\":\"a\"}"))
                .andReturn()
                .getResponse();

        assertThat(replay.getStatus()).isEqualTo(201);
        assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(handlerRuns).hasValue(1);
        com.fasterxml.jackson.databind.JsonNode before = new ObjectMapper().readTree(first);
        com.fasterxml.jackson.databind.JsonNode after =
                new ObjectMapper().readTree(replay.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(after)
                .as("jsonb có thể đổi thứ tự khoá và khoảng trắng nhưng không đổi giá trị")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("Cùng khoá, nội dung khác: 409 và handler không chạy lần hai")
    void khoa_dung_lai() throws Exception {
        UUID user = UUID.randomUUID();
        String key = "k-" + UUID.randomUUID();
        MockMvc mvc = mvc(Duration.ofMinutes(2));
        TenantContextFixture.customer(UserId.of(user));

        mvc.perform(post("/v1/orders")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"holdId\":\"a\"}"));
        int status = mvc.perform(post("/v1/orders")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdId\":\"b\"}"))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(status).isEqualTo(409);
        assertThat(handlerRuns).hasValue(1);
    }

    @Test
    @DisplayName("Hai request SONG SONG cùng khoá: handler chạy đúng một lần, request kia nhận 409 hoặc bản phát lại")
    void song_song() throws Exception {
        UUID user = UUID.randomUUID();
        String key = "k-" + UUID.randomUUID();
        MockMvc mvc = mvc(Duration.ofMinutes(2));
        CountDownLatch go = new CountDownLatch(1);

        List<Future<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            calls.add(pool.submit(() -> {
                TenantContextFixture.customer(UserId.of(user));
                go.await();
                return mvc.perform(post("/v1/orders")
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"holdId\":\"a\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> call : calls) {
            statuses.add(call.get(20, TimeUnit.SECONDS));
        }

        assertThat(handlerRuns)
                .as("không bao giờ chạy handler hai lần cho một khoá")
                .hasValue(1);
        assertThat(statuses).filteredOn(s -> s == 201).hasSizeGreaterThanOrEqualTo(1);
        assertThat(statuses).allMatch(s -> s == 201 || s == 409);
    }

    @Test
    @DisplayName("Bản ghi dở quá hạn (tiến trình đã chết) được nhận lại; chưa quá hạn thì vẫn 409")
    void nhan_lai_ban_ghi_dang() throws Exception {
        UUID user = UUID.randomUUID();
        String key = "k-" + UUID.randomUUID();
        TenantContextFixture.customer(UserId.of(user));
        // Một tiến trình đã giữ chỗ rồi chết: có bản ghi nhưng không có câu trả lời, ĐÚNG hash của request.
        MockMvc probe = mvc(Duration.ofMinutes(2));
        IdempotencyStore store = new IdempotencyStore(jdbc);
        String content = "{\"holdId\":\"a\"}";
        // Lấy hash thật bằng một lần chạy thành công trên khoá khác, rồi dựng bản ghi dở cho khoá cần thử.
        probe.perform(post("/v1/orders")
                .header("Idempotency-Key", "probe-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(content));
        String hash = jdbc.queryForObject(
                "SELECT request_hash FROM idempotency_records WHERE user_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class,
                user);
        assertThat(store.tryBegin(user, key, hash)).isTrue();
        handlerRuns.set(0);

        int fresh = mvc(Duration.ofMinutes(2))
                .perform(post("/v1/orders")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andReturn()
                .getResponse()
                .getStatus();
        assertThat(fresh)
                .as("bản ghi còn mới: request kia có thể vẫn đang chạy")
                .isEqualTo(409);
        assertThat(handlerRuns).hasValue(0);

        jdbc.update(
                "UPDATE idempotency_records SET created_at = now() - interval '10 minutes' WHERE idem_key = ?", key);
        int takenOver = mvc(Duration.ofMinutes(2))
                .perform(post("/v1/orders")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(takenOver).isEqualTo(201);
        assertThat(handlerRuns).hasValue(1);
    }
}
