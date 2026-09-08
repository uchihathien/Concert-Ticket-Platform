// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bốn mục kiểm trước khi publish một sự kiện (services.md §2).
 *
 * <p><b>Trả tất cả lỗi cùng lúc, không dừng ở lỗi đầu tiên.</b> Người dựng sự kiện đang ở màn hình
 * cấu hình với hàng chục ô nhập; báo từng lỗi một sẽ bắt họ đi qua bốn vòng sửa–thử. Đây là quyết
 * định về trải nghiệm, không phải về kỹ thuật.
 *
 * <p>Mã {@code NO_ACTIVE_BANK_ACCOUNT} của v1 đã bị gỡ: tiền vào tài khoản ký quỹ của nền tảng,
 * luôn tồn tại, nên tổ chức không cần cấu hình tài khoản nào để publish (ADR-1010 §5).
 */
public final class PublishPreflight {

    private PublishPreflight() {}

    /** Mã lỗi là hợp đồng ổn định — frontend hiện hướng dẫn sửa theo từng mã. */
    public static final String INVALID_SEATING_PLAN = "INVALID_SEATING_PLAN";

    public static final String NO_ZONE_INCLUDED = "NO_ZONE_INCLUDED";
    public static final String SEATS_WITHOUT_TIER = "SEATS_WITHOUT_TIER";
    public static final String INVALID_SALES_WINDOW = "INVALID_SALES_WINDOW";

    /**
     * @param salesOpenAt mở bán
     * @param salesCloseAt đóng bán
     * @param startsAt giờ diễn
     */
    public static Report check(SeatingPlan plan, Instant salesOpenAt, Instant salesCloseAt, Instant startsAt) {
        List<Problem> problems = new ArrayList<>();

        if (!plan.anyZoneIncluded()) {
            problems.add(new Problem(NO_ZONE_INCLUDED, "Chưa bật khu vực nào để bán"));
        }

        for (Zone zone : plan.zones()) {
            ZoneUsage usage = plan.usageOf(zone.id());
            if (usage == null || !usage.included()) {
                continue;
            }
            if (usage.ticketTierId() == null || !plan.hasTier(usage.ticketTierId())) {
                problems.add(new Problem(SEATS_WITHOUT_TIER, "Khu vực %s chưa gán hạng vé".formatted(zone.zoneCode())));
            }
            if (zone.admissionType() == AdmissionType.SEATED
                    && zone.fixedSeats().isEmpty()) {
                problems.add(new Problem(
                        INVALID_SEATING_PLAN, "Khu vực %s được bật nhưng không có chỗ nào".formatted(zone.zoneCode())));
            }
        }

        // Mã chỗ trùng nhau sẽ làm unique index của Inventory nổ giữa chừng materialize, để lại
        // một suất diễn dựng dở. Bắt ở đây, trước khi ghi bất cứ thứ gì.
        Set<String> seen = new HashSet<>();
        for (Zone zone : plan.zones()) {
            for (Zone.FixedSeat seat : zone.fixedSeats()) {
                String code = zone.zoneCode() + "-" + seat.seatCode();
                if (!seen.add(code)) {
                    problems.add(new Problem(INVALID_SEATING_PLAN, "Mã chỗ trùng: " + code));
                }
            }
        }

        if (!salesCloseAt.isAfter(salesOpenAt)) {
            problems.add(new Problem(INVALID_SALES_WINDOW, "Giờ đóng bán phải sau giờ mở bán"));
        }
        if (salesOpenAt.isAfter(startsAt)) {
            problems.add(new Problem(INVALID_SALES_WINDOW, "Không thể mở bán sau khi sự kiện đã bắt đầu"));
        }

        return new Report(problems);
    }

    public record Problem(String code, String detail) {}

    public record Report(List<Problem> problems) {

        public boolean passed() {
            return problems.isEmpty();
        }

        public List<String> codes() {
            return problems.stream().map(Problem::code).distinct().sorted().toList();
        }
    }
}
