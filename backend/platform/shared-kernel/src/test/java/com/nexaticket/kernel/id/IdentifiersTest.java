// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.kernel.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** {@link TenantId}, {@link UserId}, {@link CorrelationId} và {@link CorrelationContext}. */
class IdentifiersTest {

    private static final String UUID_TEXT = "aaaaaaaa-0000-4000-a000-000000000001";

    @AfterEach
    void don_dep() {
        CorrelationContext.clear();
    }

    @Nested
    @DisplayName("TenantId / UserId")
    class Ids {

        @Test
        @DisplayName("parse và of cho cùng một giá trị; toString là chuỗi UUID")
        void parse_of_tostring() {
            assertThat(TenantId.parse(UUID_TEXT)).isEqualTo(TenantId.of(UUID.fromString(UUID_TEXT)));
            assertThat(TenantId.parse(UUID_TEXT)).hasToString(UUID_TEXT);
            assertThat(UserId.parse(UUID_TEXT)).isEqualTo(UserId.of(UUID.fromString(UUID_TEXT)));
            assertThat(UserId.parse(UUID_TEXT)).hasToString(UUID_TEXT);
        }

        @Test
        @DisplayName("hai kiểu id không bao giờ bằng nhau dù cùng UUID — nhầm thứ tự tham số không lọt")
        void khac_kieu_khong_bang_nhau() {
            Object tenant = TenantId.parse(UUID_TEXT);
            Object user = UserId.parse(UUID_TEXT);

            assertThat(tenant).isNotEqualTo(user);
        }

        @Test
        @DisplayName("null bị từ chối; chuỗi không phải UUID ném IllegalArgumentException")
        void null_va_sai_khuon() {
            assertThatThrownBy(() -> TenantId.of(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> UserId.of(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> TenantId.parse("abc")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> UserId.parse("")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> TenantId.parse(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("dùng được làm khoá map/set")
        void khoa_map() {
            Set<TenantId> set = new HashSet<>();
            set.add(TenantId.parse(UUID_TEXT));
            set.add(TenantId.parse(UUID_TEXT.toUpperCase()));

            assertThat(set).hasSize(1);
        }
    }

    @Nested
    @DisplayName("CorrelationId")
    class Correlation {

        @Test
        @DisplayName("generate: 26 ký tự Crockford Base32 (không I, L, O, U), hoa")
        void generate_dung_khuon() {
            for (int i = 0; i < 200; i++) {
                assertThat(CorrelationId.generate().value()).matches("[0-9A-HJKMNP-TV-Z]{26}");
            }
        }

        @Test
        @DisplayName("generate: không trùng trong 10.000 lần sinh")
        void generate_khong_trung() {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 10_000; i++) {
                seen.add(CorrelationId.generate().value());
            }
            assertThat(seen).hasSize(10_000);
        }

        @Test
        @DisplayName("10 ký tự đầu mã hoá thời gian nên sắp xếp được theo thứ tự sinh")
        void sap_xep_theo_thoi_gian() throws Exception {
            String first = CorrelationId.generate().value();
            Thread.sleep(5);
            String second = CorrelationId.generate().value();

            assertThat(second.substring(0, 10)).isGreaterThanOrEqualTo(first.substring(0, 10));
            assertThat(second).isGreaterThan(first);
        }

        @Test
        @DisplayName("giá trị hợp lệ: 1 tới 64 ký tự; rỗng, trắng, 65 ký tự, null bị từ chối")
        void rang_buoc_do_dai() {
            assertThat(new CorrelationId("a").value()).isEqualTo("a");
            assertThat(new CorrelationId("x".repeat(64)).value()).hasSize(64);
            assertThatThrownBy(() -> new CorrelationId("")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new CorrelationId("   ")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new CorrelationId("x".repeat(65))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new CorrelationId(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("toString là chính giá trị")
        void to_string() {
            assertThat(new CorrelationId("abc")).hasToString("abc");
        }
    }

    @Nested
    @DisplayName("CorrelationContext")
    class Context {

        @Test
        @DisplayName("chưa set: current() là 'unknown', không bao giờ null")
        void mac_dinh_unknown() {
            assertThat(CorrelationContext.current())
                    .isEqualTo(CorrelationContext.UNKNOWN)
                    .isEqualTo("unknown");
        }

        @Test
        @DisplayName("set rồi current() trả đúng; clear() về unknown")
        void set_clear() {
            CorrelationContext.set("corr-1");
            assertThat(CorrelationContext.current()).isEqualTo("corr-1");

            CorrelationContext.clear();
            assertThat(CorrelationContext.current()).isEqualTo("unknown");
        }

        @Test
        @DisplayName("currentOrGenerate: sinh và GIỮ lại mã nếu chưa có; có rồi thì giữ nguyên")
        void current_or_generate() {
            String generated = CorrelationContext.currentOrGenerate();

            assertThat(generated).hasSize(26);
            assertThat(CorrelationContext.current()).isEqualTo(generated);
            assertThat(CorrelationContext.currentOrGenerate()).isEqualTo(generated);

            CorrelationContext.set("co-san");
            assertThat(CorrelationContext.currentOrGenerate()).isEqualTo("co-san");
        }

        @Test
        @DisplayName("ngữ cảnh theo từng luồng: luồng khác không thấy mã của luồng này")
        void theo_tung_luong() throws Exception {
            CorrelationContext.set("cua-luong-chinh");
            AtomicReference<String> other = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            new Thread(() -> {
                        other.set(CorrelationContext.current());
                        done.countDown();
                    })
                    .start();
            done.await();

            assertThat(other.get()).isEqualTo("unknown");
        }
    }
}
