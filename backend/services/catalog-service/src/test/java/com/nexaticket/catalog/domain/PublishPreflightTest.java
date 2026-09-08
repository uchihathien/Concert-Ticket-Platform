// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.catalog.domain.model.AdmissionType;
import com.nexaticket.catalog.domain.model.PublishPreflight;
import com.nexaticket.catalog.domain.model.SeatingPlan;
import com.nexaticket.catalog.domain.model.Zone;
import com.nexaticket.catalog.domain.model.ZoneKind;
import com.nexaticket.catalog.domain.model.ZoneUsage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Bốn mục preflight trước khi publish (services.md §2). */
class PublishPreflightTest {

    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private static final Instant SHOW = NOW.plus(30, ChronoUnit.DAYS);
    private static final UUID TIER = UUID.randomUUID();

    @Test
    @DisplayName("Thiết kế hợp lệ: không có lỗi nào")
    void thiet_ke_hop_le() {
        Zone zone = seatedZone("A", 5);

        var report = check(List.of(zone), List.of(new ZoneUsage(zone.id(), true, TIER, null)));

        assertThat(report.passed()).isTrue();
    }

    @Test
    @DisplayName("Không bật khu vực nào: NO_ZONE_INCLUDED")
    void khong_bat_khu_vuc_nao() {
        Zone zone = seatedZone("A", 5);

        var report = check(List.of(zone), List.of(new ZoneUsage(zone.id(), false, null, null)));

        assertThat(report.codes()).contains(PublishPreflight.NO_ZONE_INCLUDED);
    }

    @Test
    @DisplayName("Khu vực bật nhưng chưa gán hạng vé: SEATS_WITHOUT_TIER")
    void chua_gan_hang_ve() {
        Zone zone = seatedZone("A", 5);

        var report = check(List.of(zone), List.of(new ZoneUsage(zone.id(), true, null, null)));

        assertThat(report.codes()).contains(PublishPreflight.SEATS_WITHOUT_TIER);
    }

    @Test
    @DisplayName("Mã chỗ trùng nhau: INVALID_SEATING_PLAN")
    void ma_cho_trung() {
        // Để lọt xuống Inventory thì unique index nổ giữa chừng materialize, để lại một suất
        // diễn dựng dở — bắt ở đây, trước khi ghi bất cứ thứ gì.
        Zone zone = new Zone(
                UUID.randomUUID(),
                "A",
                "Khan dai A",
                ZoneKind.FIXED,
                AdmissionType.SEATED,
                null,
                List.of(
                        new Zone.FixedSeat(UUID.randomUUID(), "A-1", "1", "1", null, null),
                        new Zone.FixedSeat(UUID.randomUUID(), "A-1", "1", "1", null, null)));

        var report = check(List.of(zone), List.of(new ZoneUsage(zone.id(), true, TIER, null)));

        assertThat(report.codes()).contains(PublishPreflight.INVALID_SEATING_PLAN);
    }

    @Test
    @DisplayName("Giờ đóng bán trước giờ mở bán: INVALID_SALES_WINDOW")
    void khung_gio_ban_sai() {
        Zone zone = seatedZone("A", 5);
        var plan = plan(List.of(zone), List.of(new ZoneUsage(zone.id(), true, TIER, null)));

        var report = PublishPreflight.check(plan, NOW.plus(5, ChronoUnit.DAYS), NOW, SHOW);

        assertThat(report.codes()).contains(PublishPreflight.INVALID_SALES_WINDOW);
    }

    @Test
    @DisplayName("Trả TẤT CẢ lỗi cùng lúc, không dừng ở lỗi đầu tiên")
    void tra_tat_ca_loi_cung_luc() {
        // Người dựng sự kiện đang ở màn hình có hàng chục ô nhập; báo từng lỗi một sẽ bắt họ
        // đi qua nhiều vòng sửa–thử.
        Zone zone = seatedZone("A", 5);
        var plan = plan(List.of(zone), List.of(new ZoneUsage(zone.id(), true, null, null)));

        var report = PublishPreflight.check(plan, NOW.plus(5, ChronoUnit.DAYS), NOW, SHOW);

        assertThat(report.codes()).contains(PublishPreflight.SEATS_WITHOUT_TIER, PublishPreflight.INVALID_SALES_WINDOW);
    }

    private static PublishPreflight.Report check(List<Zone> zones, List<ZoneUsage> usages) {
        return PublishPreflight.check(plan(zones, usages), NOW, SHOW.minus(1, ChronoUnit.HOURS), SHOW);
    }

    private static SeatingPlan plan(List<Zone> zones, List<ZoneUsage> usages) {
        return new SeatingPlan(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                zones,
                usages,
                List.of(),
                List.of(new SeatingPlan.TicketTier(TIER, "Ve ngoi", 1_500_000L)));
    }

    private static Zone seatedZone(String code, int seatCount) {
        List<Zone.FixedSeat> seats = new java.util.ArrayList<>();
        for (int i = 1; i <= seatCount; i++) {
            seats.add(new Zone.FixedSeat(UUID.randomUUID(), code + "-" + i, "1", String.valueOf(i), null, null));
        }
        return new Zone(UUID.randomUUID(), code, "Khan dai " + code, ZoneKind.FIXED, AdmissionType.SEATED, null, seats);
    }
}
