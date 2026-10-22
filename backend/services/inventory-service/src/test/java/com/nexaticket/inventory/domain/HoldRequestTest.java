// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.inventory.domain.model.HoldRequest;
import com.nexaticket.inventory.domain.model.SeatedZoneRequest;
import com.nexaticket.inventory.domain.model.StandingRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Hình dạng một yêu cầu giữ chỗ: khử trùng lặp, gộp dòng, đếm — thuần domain. */
class HoldRequestTest {

    private static final UUID SEAT_A = UUID.fromString("00000000-0000-4000-a000-00000000000a");
    private static final UUID SEAT_B = UUID.fromString("00000000-0000-4000-a000-00000000000b");
    private static final UUID SEAT_C = UUID.fromString("00000000-0000-4000-a000-00000000000c");

    @Nested
    @DisplayName("Dựng yêu cầu")
    class DungYeuCau {

        @Test
        @DisplayName("Danh sách null được coi là rỗng, không ném NullPointerException")
        void danh_sach_null_la_rong() {
            var request = new HoldRequest(null, null, null);

            assertThat(request.seatIds()).isEmpty();
            assertThat(request.seatedZones()).isEmpty();
            assertThat(request.standing()).isEmpty();
            assertThat(request.totalUnits()).isZero();
            assertThat(request.hasSeated()).isFalse();
            assertThat(request.hasStanding()).isFalse();
            assertThat(request.hasNamedSeats()).isFalse();
        }

        @Test
        @DisplayName("Khử trùng lặp ghế nhưng giữ đúng thứ tự khách bấm")
        void khu_trung_lap_giu_thu_tu() {
            var request = new HoldRequest(List.of(SEAT_C, SEAT_A, SEAT_C, SEAT_B, SEAT_A), List.of(), List.of());

            assertThat(request.seatIds()).containsExactly(SEAT_C, SEAT_A, SEAT_B);
        }

        @Test
        @DisplayName("Sửa danh sách gốc sau khi dựng không làm đổi yêu cầu")
        void sao_chep_phong_thu() {
            List<UUID> raw = new ArrayList<>(List.of(SEAT_A));
            var request = new HoldRequest(raw, List.of(), List.of());

            raw.add(SEAT_B);

            assertThat(request.seatIds()).containsExactly(SEAT_A);
            assertThatThrownBy(() -> request.seatIds().add(SEAT_C)).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("Hai dòng vé ngồi cùng khu gộp thành một, giữ thứ tự khu xuất hiện đầu tiên")
        void gop_ve_ngoi_cung_khu() {
            var request = new HoldRequest(
                    List.of(),
                    List.of(
                            new SeatedZoneRequest("B", 1),
                            new SeatedZoneRequest("A", 2),
                            new SeatedZoneRequest("B", 3)),
                    List.of());

            assertThat(request.seatedZones())
                    .containsExactly(new SeatedZoneRequest("B", 4), new SeatedZoneRequest("A", 2));
        }

        @Test
        @DisplayName("Cùng mã khu ở vé ngồi và vé đứng không bị gộp lẫn sang nhau")
        void khong_gop_cheo_ngoi_dung() {
            var request = new HoldRequest(
                    List.of(), List.of(new SeatedZoneRequest("A", 2)), List.of(new StandingRequest("A", 3)));

            assertThat(request.seatedCount()).isEqualTo(2);
            assertThat(request.standingCount()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Đếm theo trần")
    class DemTheoTran {

        @Test
        @DisplayName("seatedCount cộng cả ghế đích danh lẫn vé ngồi theo khu")
        void seated_count_gom_hai_duong() {
            var request = new HoldRequest(
                    List.of(SEAT_A, SEAT_B),
                    List.of(new SeatedZoneRequest("A", 3)),
                    List.of(new StandingRequest("GA", 4)));

            assertThat(request.seatedCount()).isEqualTo(5);
            assertThat(request.standingCount()).isEqualTo(4);
            assertThat(request.totalUnits()).isEqualTo(9);
        }

        @Test
        @DisplayName("Chỉ có vé ngồi theo khu: là vé ngồi nhưng KHÔNG đi qua cổng Redis")
        void ve_theo_khu_khong_qua_cong() {
            var request = new HoldRequest(List.of(), List.of(new SeatedZoneRequest("A", 2)), List.of());

            assertThat(request.hasSeated()).isTrue();
            assertThat(request.hasNamedSeats()).isFalse();
        }

        @Test
        @DisplayName("Gộp hai dòng cùng khu mà tổng tràn số nguyên: từ chối, không ra số âm lọt qua trần")
        void gop_tran_so_bi_tu_choi() {
            assertThatThrownBy(() -> new HoldRequest(
                            List.of(),
                            List.of(),
                            List.of(new StandingRequest("GA", Integer.MAX_VALUE), new StandingRequest("GA", 1))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Kiểm tra dòng theo khu")
    class KiemTraDong {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("Mã khu rỗng hoặc trắng bị từ chối ở cả vé ngồi lẫn vé đứng")
        void ma_khu_rong(String zone) {
            assertThatThrownBy(() -> new StandingRequest(zone, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SeatedZoneRequest(zone, 1)).isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
        @DisplayName("Số lượng không dương bị từ chối ở cả vé ngồi lẫn vé đứng")
        void so_luong_khong_duong(int quantity) {
            assertThatThrownBy(() -> new StandingRequest("GA", quantity))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(String.valueOf(quantity));
            assertThatThrownBy(() -> new SeatedZoneRequest("A", quantity))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(String.valueOf(quantity));
        }

        @Test
        @DisplayName("Một vé là số lượng nhỏ nhất hợp lệ")
        void mot_ve_hop_le() {
            assertThat(new StandingRequest("GA", 1).quantity()).isEqualTo(1);
            assertThat(new SeatedZoneRequest("A", 1).quantity()).isEqualTo(1);
        }
    }
}
