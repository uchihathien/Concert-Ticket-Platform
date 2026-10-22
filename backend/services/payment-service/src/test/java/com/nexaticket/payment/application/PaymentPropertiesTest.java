// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Cấu hình payOS: mặc định an toàn, thiếu khoá thì "chưa cấu hình" chứ không phải khởi động hỏng. */
class PaymentPropertiesTest {

    private static PaymentProperties.Payos payos(String clientId, String apiKey, String checksumKey) {
        return new PaymentProperties.Payos(null, clientId, apiKey, checksumKey, null, null, null, null);
    }

    @Test
    @DisplayName("Thiếu cả khối payos: không khởi động được")
    void thieu_khoi_payos() {
        assertThatThrownBy(() -> new PaymentProperties(false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nexaticket.payment.payos");
    }

    @Nested
    @DisplayName("Giá trị mặc định")
    class MacDinh {

        @Test
        @DisplayName("Để trống: gốc API payOS thật, web localhost:3000, timeout 3s/10s")
        void de_trong_thi_dung_mac_dinh() {
            PaymentProperties.Payos config = new PaymentProperties.Payos(" ", null, null, null, "", null, null, null);

            assertThat(config.baseUrl()).isEqualTo("https://api-merchant.payos.vn");
            assertThat(config.webBaseUrl()).isEqualTo("http://localhost:3000");
            assertThat(config.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(config.readTimeout()).isEqualTo(Duration.ofSeconds(10));
        }

        @Test
        @DisplayName("Dấu '/' cuối URL bị cắt để không sinh '//' trong đường dẫn")
        void cat_dau_gach_cuoi() {
            PaymentProperties.Payos config = new PaymentProperties.Payos(
                    "https://api.example/", null, null, null, "https://web.example/", null, null, null);

            assertThat(config.baseUrl()).isEqualTo("https://api.example");
            UUID orderId = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");
            assertThat(config.returnUrl(orderId))
                    .isEqualTo("https://web.example/orders/0f0e0d0c-0b0a-4908-8706-050403020100/pay");
            assertThat(config.cancelUrl(orderId)).isEqualTo(config.returnUrl(orderId));
        }

        @Test
        @DisplayName("Timeout khai tường minh được giữ nguyên")
        void timeout_tuong_minh() {
            PaymentProperties.Payos config = new PaymentProperties.Payos(
                    null, null, null, null, null, null, Duration.ofMillis(500), Duration.ofSeconds(2));

            assertThat(config.connectTimeout()).isEqualTo(Duration.ofMillis(500));
            assertThat(config.readTimeout()).isEqualTo(Duration.ofSeconds(2));
        }
    }

    @Nested
    @DisplayName("Đã cấu hình hay chưa")
    class DaCauHinh {

        @Test
        @DisplayName("Đủ ba khoá: đã cấu hình")
        void du_ba_khoa() {
            assertThat(payos("client", "api", "checksum").configured()).isTrue();
        }

        @ParameterizedTest(name = "clientId={0}, apiKey={1}, checksumKey={2}")
        @DisplayName("Thiếu hoặc trắng bất kỳ khoá nào: CHƯA cấu hình")
        @CsvSource(
                value = {
                    "NULL,api,checksum",
                    "client,NULL,checksum",
                    "client,api,NULL",
                    "' ',api,checksum",
                    "client,api,'   '"
                },
                nullValues = "NULL")
        void thieu_mot_khoa(String clientId, String apiKey, String checksumKey) {
            assertThat(payos(clientId, apiKey, checksumKey).configured()).isFalse();
        }
    }
}
