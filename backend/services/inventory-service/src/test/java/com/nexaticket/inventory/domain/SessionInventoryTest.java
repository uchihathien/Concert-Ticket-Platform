// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.inventory.domain.model.LimitViolation;
import com.nexaticket.inventory.domain.model.PurchaseLimits;
import com.nexaticket.inventory.domain.model.SessionInventory;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Khung giờ bán, hạn giữ chỗ và biên của trần mua vé. */
class SessionInventoryTest {

    private static final Instant OPEN = Instant.parse("2026-10-22T09:00:00Z");
    private static final Instant CLOSE = Instant.parse("2026-10-22T21:00:00Z");

    private static final SessionInventory INVENTORY = new SessionInventory(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            OPEN,
            CLOSE,
            new PurchaseLimits(4, 6, 8, 10),
            Duration.ofMinutes(10));

    @Nested
    @DisplayName("Khung giờ bán")
    class KhungGioBan {

        @Test
        @DisplayName("Đúng giờ mở là đã mở — biên trái đóng")
        void dung_gio_mo() {
            assertThat(INVENTORY.isSalesOpenAt(OPEN)).isTrue();
            assertThat(INVENTORY.isSalesOpenAt(OPEN.minusNanos(1))).isFalse();
        }

        @Test
        @DisplayName("Đúng giờ đóng là đã đóng — biên phải mở")
        void dung_gio_dong() {
            assertThat(INVENTORY.isSalesOpenAt(CLOSE)).isFalse();
            assertThat(INVENTORY.isSalesOpenAt(CLOSE.minusNanos(1))).isTrue();
        }
    }

    @Test
    @DisplayName("Hạn giữ chỗ tính từ thời điểm truyền vào cộng TTL của suất")
    void han_giu_cho() {
        Instant now = Instant.parse("2026-10-22T10:00:00Z");

        assertThat(INVENTORY.holdExpiryFrom(now)).isEqualTo(Instant.parse("2026-10-22T10:10:00Z"));
    }

    @Nested
    @DisplayName("Biên của trần mua vé")
    class BienTranMuaVe {

        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        @DisplayName("Trần không dương bị từ chối ở cả bốn ô — trần 0 là suất không bán được gì")
        void tran_khong_duong(int bad) {
            assertThatThrownBy(() -> new PurchaseLimits(bad, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PurchaseLimits(1, bad, 1, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PurchaseLimits(1, 1, bad, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PurchaseLimits(1, 1, 1, bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maxTicketsPerCustomer");
        }

        @Test
        @DisplayName("Đúng bằng trần là đạt, vượt một là vi phạm — cho từng ô")
        void dung_bang_tran() {
            PurchaseLimits limits = INVENTORY.limits();

            assertThat(limits.checkHoldSize(4, 0)).isEmpty();
            assertThat(limits.checkHoldSize(5, 0)).contains(LimitViolation.TOO_MANY_SEATED);
            assertThat(limits.checkHoldSize(0, 6)).isEmpty();
            assertThat(limits.checkHoldSize(0, 7)).contains(LimitViolation.TOO_MANY_STANDING);
            assertThat(limits.checkHoldSize(4, 4)).isEmpty();
            assertThat(limits.checkHoldSize(4, 5)).contains(LimitViolation.TOO_MANY_UNITS);
            assertThat(limits.checkCustomerTotal(0, 10)).isEmpty();
            assertThat(limits.checkCustomerTotal(0, 11)).contains(LimitViolation.CUSTOMER_TOTAL_EXCEEDED);
        }

        @Test
        @DisplayName("Vượt cả trần ngồi lẫn trần đứng: báo vé ngồi trước, thứ tự kiểm cố định")
        void thu_tu_bao_loi() {
            assertThat(INVENTORY.limits().checkHoldSize(5, 7)).contains(LimitViolation.TOO_MANY_SEATED);
        }

        @Test
        @DisplayName("Số còn mua được không bao giờ âm, kể cả khi dữ liệu cũ đã vượt trần")
        void con_lai_khong_am() {
            assertThat(INVENTORY.limits().remainingFor(10)).isZero();
            assertThat(INVENTORY.limits().remainingFor(11)).isZero();
            assertThat(INVENTORY.limits().remainingFor(0)).isEqualTo(10);
        }
    }
}
