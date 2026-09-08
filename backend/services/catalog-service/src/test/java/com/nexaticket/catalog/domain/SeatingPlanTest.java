// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.catalog.domain.model.AdmissionType;
import com.nexaticket.catalog.domain.model.SeatManifest;
import com.nexaticket.catalog.domain.model.SeatOverride;
import com.nexaticket.catalog.domain.model.SeatingPlan;
import com.nexaticket.catalog.domain.model.Zone;
import com.nexaticket.catalog.domain.model.ZoneKind;
import com.nexaticket.catalog.domain.model.ZoneUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sinh danh sách chỗ từ thiết kế — thuần domain, không cần database.
 *
 * <p>Đây là chỗ hai chiều của mô hình khu vực (ADR-1012) gặp nhau và biến thành một danh sách
 * phẳng. Ba kiểu concert đều phải rơi ra từ cùng một đoạn code này.
 */
class SeatingPlanTest {

    private static final SeatManifest.ResolvedPurchaseLimits LIMITS =
            new SeatManifest.ResolvedPurchaseLimits(8, 10, 10, 10);

    private static final UUID SEATED_TIER = UUID.randomUUID();
    private static final UUID STANDING_TIER = UUID.randomUUID();
    private static final UUID VIP_TIER = UUID.randomUUID();

    @Test
    @DisplayName("Concert toàn ghế ngồi: mọi chỗ áp cứng thành một dòng trong danh sách")
    void concert_toan_ghe_ngoi() {
        Zone khanDai = seatedZone("A", "Khan dai A", ZoneKind.FIXED, 10);

        var manifest = plan(List.of(khanDai), List.of(include(khanDai, SEATED_TIER)), List.of())
                .materialize(LIMITS);

        assertThat(manifest.seats()).hasSize(10);
        assertThat(manifest.standingBlocks()).isEmpty();
        assertThat(manifest.totalSellableCount()).isEqualTo(10);
        assertThat(manifest.seats().get(0).seatCode()).isEqualTo("A-A-1");
    }

    @Test
    @DisplayName("Concert chỉ đứng: một khối có số lượng, KHÔNG phải 3.000 phần tử")
    void concert_chi_dung() {
        // Truyền từng đơn vị ảo qua message là 3.000 phần tử vô nghĩa; Inventory tự sinh được.
        Zone sanDung = standingZone("GA", 3_000);

        var manifest = plan(List.of(sanDung), List.of(include(sanDung, STANDING_TIER)), List.of())
                .materialize(LIMITS);

        assertThat(manifest.seats()).isEmpty();
        assertThat(manifest.standingBlocks()).hasSize(1);
        assertThat(manifest.standingBlocks().get(0).quantity()).isEqualTo(3_000);
        assertThat(manifest.totalSellableCount()).isEqualTo(3_000);
    }

    @Test
    @DisplayName("Concert vừa ngồi vừa đứng: cả hai loại trong một danh sách")
    void concert_hon_hop() {
        Zone khanDai = seatedZone("A", "Khan dai A", ZoneKind.FIXED, 5);
        Zone sanDung = standingZone("GA", 200);

        var manifest = plan(
                        List.of(khanDai, sanDung),
                        List.of(include(khanDai, SEATED_TIER), include(sanDung, STANDING_TIER)),
                        List.of())
                .materialize(LIMITS);

        assertThat(manifest.totalSeatedCount()).isEqualTo(5);
        assertThat(manifest.totalStandingCount()).isEqualTo(200);
        assertThat(manifest.totalSellableCount()).isEqualTo(205);
    }

    @Test
    @DisplayName("Khu vực tắt thì không sinh chỗ nào")
    void khu_vuc_tat() {
        Zone khanDai = seatedZone("A", "Khan dai A", ZoneKind.FIXED, 10);
        Zone khanDaiB = seatedZone("B", "Khan dai B", ZoneKind.FIXED, 10);

        var manifest = plan(
                        List.of(khanDai, khanDaiB),
                        List.of(include(khanDai, SEATED_TIER), exclude(khanDaiB)),
                        List.of())
                .materialize(LIMITS);

        assertThat(manifest.seats()).hasSize(10);
        assertThat(manifest.seats()).allMatch(seat -> seat.zoneCode().equals("A"));
    }

    @Test
    @DisplayName("REMOVE bỏ hẳn chỗ; BLOCK giữ chỗ nhưng không bán")
    void remove_khac_block() {
        // BLOCK phải GIỮ chỗ lại: sơ đồ vẫn cần hiển thị đúng hình dạng khán phòng, xoá đi thì
        // khách thấy một lỗ hổng khó hiểu ở giữa hàng ghế.
        Zone khanDai = seatedZone("A", "Khan dai A", ZoneKind.FIXED, 5);

        var manifest = plan(
                        List.of(khanDai),
                        List.of(include(khanDai, SEATED_TIER)),
                        List.of(
                                new SeatOverride(khanDai.id(), "A-1", SeatOverride.Action.REMOVE, null),
                                new SeatOverride(khanDai.id(), "A-2", SeatOverride.Action.BLOCK, null)))
                .materialize(LIMITS);

        assertThat(manifest.seats()).hasSize(4);
        assertThat(manifest.seats()).filteredOn(SeatManifest.SeatLine::blocked).hasSize(1);
        assertThat(manifest.totalSeatedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("SET_TIER đổi hạng vé cho riêng một chỗ")
    void set_tier_cho_mot_cho() {
        Zone khanDai = seatedZone("A", "Khan dai A", ZoneKind.FIXED, 3);

        var manifest = plan(
                        List.of(khanDai),
                        List.of(include(khanDai, SEATED_TIER)),
                        List.of(new SeatOverride(khanDai.id(), "A-1", SeatOverride.Action.SET_TIER, VIP_TIER)))
                .materialize(LIMITS);

        assertThat(manifest.seats())
                .filteredOn(seat -> seat.priceVnd() == 5_000_000L)
                .hasSize(1);
        assertThat(manifest.seats())
                .filteredOn(seat -> seat.priceVnd() == 1_500_000L)
                .hasSize(2);
    }

    @Test
    @DisplayName("Tổ chức bán ít hơn sức chứa được, nhiều hơn thì không")
    void suc_chua_ve_dung_bi_kep() {
        // Bán ít hơn là quyền của tổ chức (chừa lối đi). Bán nhiều hơn thì không — sức chứa là
        // ràng buộc vật lý và phòng cháy của địa điểm.
        Zone sanDung = standingZone("GA", 200);

        var itHon = plan(List.of(sanDung), List.of(includeStanding(sanDung, STANDING_TIER, 150)), List.of())
                .materialize(LIMITS);
        var nhieuHon = plan(List.of(sanDung), List.of(includeStanding(sanDung, STANDING_TIER, 500)), List.of())
                .materialize(LIMITS);

        assertThat(itHon.totalStandingCount()).isEqualTo(150);
        assertThat(nhieuHon.totalStandingCount()).isEqualTo(200);
    }

    // --- dựng dữ liệu ---

    private static Zone seatedZone(String code, String name, ZoneKind kind, int seatCount) {
        List<Zone.FixedSeat> seats = new ArrayList<>();
        for (int i = 1; i <= seatCount; i++) {
            seats.add(new Zone.FixedSeat(UUID.randomUUID(), code + "-" + i, "1", String.valueOf(i), null, null));
        }
        return new Zone(UUID.randomUUID(), code, name, kind, AdmissionType.SEATED, null, seats);
    }

    private static Zone standingZone(String code, int capacity) {
        return new Zone(
                UUID.randomUUID(), code, "San dung", ZoneKind.FLEXIBLE, AdmissionType.STANDING, capacity, List.of());
    }

    private static ZoneUsage include(Zone zone, UUID tierId) {
        return new ZoneUsage(zone.id(), true, tierId, null);
    }

    private static ZoneUsage includeStanding(Zone zone, UUID tierId, int capacity) {
        return new ZoneUsage(zone.id(), true, tierId, capacity);
    }

    private static ZoneUsage exclude(Zone zone) {
        return new ZoneUsage(zone.id(), false, null, null);
    }

    private static SeatingPlan plan(List<Zone> zones, List<ZoneUsage> usages, List<SeatOverride> overrides) {
        return new SeatingPlan(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                zones,
                usages,
                overrides,
                List.of(
                        new SeatingPlan.TicketTier(SEATED_TIER, "Ve ngoi", 1_500_000L),
                        new SeatingPlan.TicketTier(STANDING_TIER, "Ve dung", 800_000L),
                        new SeatingPlan.TicketTier(VIP_TIER, "VIP", 5_000_000L)));
    }
}
