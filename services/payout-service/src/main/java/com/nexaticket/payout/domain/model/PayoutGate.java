// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payout.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Sáu cổng chặn trước khi chi trả (services.md §8) — <b>hàm thuần</b>.
 *
 * <p>Đây là danh sách kiểm cuối cùng trước khi tiền rời khỏi tài khoản ký quỹ. Mỗi cổng tương ứng
 * một cách mất tiền đã từng xảy ra ở đâu đó, nên không cổng nào là thừa:
 *
 * <ol>
 *   <li><b>Hold period</b> — chi trước khi hết hạn hoàn vé thì tiền hoàn cho khách lấy từ đâu.
 *   <li><b>Số dư khả dụng</b> — chi quá số dư là nền tảng lấy tiền của tổ chức khác trả cho tổ
 *       chức này, và sổ sách sẽ không bao giờ khớp lại được.
 *   <li><b>Đối soát hôm trước đã đóng</b> — chi khi chưa biết chắc hôm qua thu bao nhiêu là chi
 *       trên một con số có thể sai.
 *   <li><b>Tổ chức không bị đình chỉ</b> — đình chỉ thường vì nghi ngờ gian lận; chi tiếp là
 *       chuyển tiền ra ngoài tầm với.
 *   <li><b>Không còn khoản phải thu</b> — còn nợ mà vẫn chi là mất luôn cơ hội bù trừ.
 *   <li><b>Tên chủ tài khoản khớp hồ sơ</b> — chốt chặn cuối chống đổi số tài khoản ngay trước
 *       đợt chi trả.
 * </ol>
 *
 * <p>Cổng {@code KYC_REQUIRED} của ADR-1007 đã gỡ: superadmin thẩm định trước khi tạo tổ chức,
 * nên cổng đó giờ là con người chứ không phải phần mềm.
 */
public final class PayoutGate {

    public static final String HOLD_PERIOD_NOT_ELAPSED = "HOLD_PERIOD_NOT_ELAPSED";
    public static final String INSUFFICIENT_BALANCE = "INSUFFICIENT_BALANCE";
    public static final String RECONCILIATION_PENDING = "RECONCILIATION_PENDING";
    public static final String ORGANIZATION_SUSPENDED = "ORGANIZATION_SUSPENDED";
    public static final String OUTSTANDING_RECEIVABLE = "OUTSTANDING_RECEIVABLE";
    public static final String PAYOUT_ACCOUNT_NAME_MISMATCH = "PAYOUT_ACCOUNT_NAME_MISMATCH";

    private PayoutGate() {}

    /**
     * @param requestedVnd số tiền muốn chi
     * @param availableVnd số dư khả dụng của tổ chức (tài khoản 2012 trong sổ cái)
     * @param receivableVnd khoản phải thu còn treo (tài khoản 1320)
     * @param holdPeriodElapsed đã qua hạn giữ tiền sau sự kiện chưa
     * @param reconciliationClosed đối soát ngày làm việc trước đã đóng chưa
     * @param organizationActive tổ chức còn hoạt động không
     * @param accountHolder tên chủ tài khoản trên đích chi trả
     * @param legalName tên pháp nhân trong hồ sơ tổ chức
     */
    @SuppressWarnings("java:S107") // Sáu cổng cần sáu dữ kiện; gom thành DTO chỉ đổi chỗ đặt tham số.
    public static Decision evaluate(
            long requestedVnd,
            long availableVnd,
            long receivableVnd,
            boolean holdPeriodElapsed,
            boolean reconciliationClosed,
            boolean organizationActive,
            String accountHolder,
            String legalName) {

        List<String> blockers = new ArrayList<>();

        if (!holdPeriodElapsed) {
            blockers.add(HOLD_PERIOD_NOT_ELAPSED);
        }
        if (requestedVnd > availableVnd) {
            blockers.add(INSUFFICIENT_BALANCE);
        }
        if (!reconciliationClosed) {
            blockers.add(RECONCILIATION_PENDING);
        }
        if (!organizationActive) {
            blockers.add(ORGANIZATION_SUSPENDED);
        }
        if (receivableVnd != 0) {
            blockers.add(OUTSTANDING_RECEIVABLE);
        }
        if (!namesMatch(accountHolder, legalName)) {
            blockers.add(PAYOUT_ACCOUNT_NAME_MISMATCH);
        }
        return new Decision(blockers);
    }

    /**
     * So khớp tên đã chuẩn hoá.
     *
     * <p>Ngân hàng trả tên viết hoa không dấu, còn hồ sơ pháp nhân thì có dấu và có hậu tố kiểu
     * "Công ty TNHH". So khớp nguyên văn sẽ chặn gần như mọi lần chi trả hợp lệ, nên phải bỏ dấu,
     * bỏ khoảng trắng thừa và so bằng chữ hoa.
     *
     * <p>Đây là so khớp <b>lỏng</b> có chủ ý và chỉ là một trong sáu cổng; chốt chặn thật là việc
     * chỉ superadmin nhập được đích chi trả.
     */
    private static boolean namesMatch(String accountHolder, String legalName) {
        return normalize(accountHolder).equals(normalize(legalName));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String withoutMarks = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace("đ", "d")
                .replace("Đ", "D");
        return withoutMarks.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }

    /** @param blockers các cổng không qua; rỗng nghĩa là chi trả được */
    public record Decision(List<String> blockers) {

        public boolean allowed() {
            return blockers.isEmpty();
        }
    }
}
