// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.inventory.domain.model.HoldStatus;
import com.nexaticket.inventory.domain.model.SeatHold;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Vòng đời aggregate giữ chỗ — đồng hồ truyền vào, không có {@code Instant.now()}. */
class SeatHoldTest {

    private static final Instant EXPIRES = Instant.parse("2026-10-22T10:10:00Z");
    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-a000-000000000001");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-4000-a000-000000000002");
    private static final List<UUID> SEATS = List.of(UUID.randomUUID(), UUID.randomUUID());

    private static SeatHold activeHold() {
        return SeatHold.active(UUID.randomUUID(), UUID.randomUUID(), OWNER, EXPIRES, SEATS);
    }

    @Nested
    @DisplayName("Khởi tạo")
    class KhoiTao {

        @Test
        @DisplayName("Giữ chỗ rỗng bị từ chối ngay từ factory")
        void giu_cho_rong_bi_tu_choi() {
            assertThatThrownBy(() -> SeatHold.active(UUID.randomUUID(), UUID.randomUUID(), OWNER, EXPIRES, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Giữ chỗ mới ở trạng thái ACTIVE, kích thước bằng số chỗ")
        void moi_tao_la_active() {
            SeatHold hold = activeHold();

            assertThat(hold.status()).isEqualTo(HoldStatus.ACTIVE);
            assertThat(hold.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("Danh sách chỗ được sao chép: sửa danh sách gốc không đổi aggregate")
        void sao_chep_danh_sach_cho() {
            List<UUID> raw = new ArrayList<>(SEATS);
            SeatHold hold = SeatHold.active(UUID.randomUUID(), UUID.randomUUID(), OWNER, EXPIRES, raw);

            raw.clear();

            assertThat(hold.seatIds()).containsExactlyElementsOf(SEATS);
        }
    }

    @Nested
    @DisplayName("Hạn giữ chỗ")
    class HanGiuCho {

        @Test
        @DisplayName("Một nano giây trước hạn vẫn dùng được")
        void truoc_han_dung_duoc() {
            SeatHold hold = activeHold();

            assertThat(hold.isExpiredAt(EXPIRES.minusNanos(1))).isFalse();
            assertThat(hold.isUsableAt(EXPIRES.minusNanos(1))).isTrue();
        }

        @Test
        @DisplayName("Đúng thời điểm hết hạn đã coi là hết hạn — biên đóng")
        void dung_han_la_het_han() {
            SeatHold hold = activeHold();

            assertThat(hold.isExpiredAt(EXPIRES)).isTrue();
            assertThat(hold.isUsableAt(EXPIRES)).isFalse();
        }

        @ParameterizedTest
        @EnumSource(value = HoldStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("Giữ chỗ không còn ACTIVE thì không dùng được dù chưa hết hạn")
        void khong_active_khong_dung_duoc(HoldStatus status) {
            SeatHold hold = SeatHold.rehydrate(UUID.randomUUID(), UUID.randomUUID(), OWNER, EXPIRES, SEATS, status);

            assertThat(hold.isUsableAt(EXPIRES.minusSeconds(60))).isFalse();
        }
    }

    @Nested
    @DisplayName("Chuyển trạng thái")
    class ChuyenTrangThai {

        @Test
        @DisplayName("Chuyển thành đơn khi còn hạn: CONVERTED")
        void convert_khi_con_han() {
            SeatHold hold = activeHold();

            hold.convert(EXPIRES.minusSeconds(1));

            assertThat(hold.status()).isEqualTo(HoldStatus.CONVERTED);
        }

        @Test
        @DisplayName("Chuyển thành đơn khi đã hết hạn: ném lỗi và giữ nguyên ACTIVE")
        void convert_khi_het_han() {
            SeatHold hold = activeHold();

            assertThatThrownBy(() -> hold.convert(EXPIRES)).isInstanceOf(IllegalStateException.class);
            assertThat(hold.status()).isEqualTo(HoldStatus.ACTIVE);
        }

        @Test
        @DisplayName("Chuyển thành đơn hai lần: lần hai ném lỗi")
        void convert_hai_lan() {
            SeatHold hold = activeHold();
            hold.convert(EXPIRES.minusSeconds(1));

            assertThatThrownBy(() -> hold.convert(EXPIRES.minusSeconds(1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CONVERTED");
        }

        @Test
        @DisplayName("Nhả rồi nhả lại: idempotent, vẫn RELEASED")
        void release_idempotent() {
            SeatHold hold = activeHold();

            hold.release();
            hold.release();

            assertThat(hold.status()).isEqualTo(HoldStatus.RELEASED);
        }

        @Test
        @DisplayName("Giữ chỗ đã thành đơn KHÔNG bị nhả hay hết hạn đè lên — chỗ đã đặt không được quay về")
        void da_convert_khong_bi_nha() {
            SeatHold hold = activeHold();
            hold.convert(EXPIRES.minusSeconds(1));

            hold.release();
            hold.expire();

            assertThat(hold.status()).isEqualTo(HoldStatus.CONVERTED);
        }

        @Test
        @DisplayName("Đã nhả thì worker hết hạn không ghi đè thành EXPIRED")
        void da_nha_khong_thanh_het_han() {
            SeatHold hold = activeHold();
            hold.release();

            hold.expire();

            assertThat(hold.status()).isEqualTo(HoldStatus.RELEASED);
        }

        @Test
        @DisplayName("Hết hạn từ ACTIVE: EXPIRED, và sau đó không chuyển thành đơn được")
        void het_han_roi_khong_convert() {
            SeatHold hold = activeHold();

            hold.expire();

            assertThat(hold.status()).isEqualTo(HoldStatus.EXPIRED);
            assertThatThrownBy(() -> hold.convert(EXPIRES.minusSeconds(1))).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("Chỉ đúng chủ sở hữu được nhận là chủ; null không phải chủ")
    void quyen_so_huu() {
        SeatHold hold = activeHold();

        assertThat(hold.isOwnedBy(OWNER)).isTrue();
        assertThat(hold.isOwnedBy(STRANGER)).isFalse();
        assertThat(hold.isOwnedBy(null)).isFalse();
    }
}
