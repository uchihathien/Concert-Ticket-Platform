// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ledger.domain.model;

import java.util.regex.Pattern;

/**
 * Mã tài khoản trong hệ thống tài khoản (custodial-funds.md §2).
 *
 * <p>Các mã có nghĩa cố định, khai ở đây để không ai gõ chuỗi ma thuật rải rác trong code.
 */
public record AccountCode(String value) {

    private static final Pattern VALID = Pattern.compile("^[0-9]{4}$");

    /** Tiền trong tài khoản ký quỹ của nền tảng. */
    public static final AccountCode CASH_ESCROW = new AccountCode("1010");

    /** Tiền trong tài khoản vận hành của nền tảng. */
    public static final AccountCode CASH_OPERATING = new AccountCode("1020");

    /** Khoản tổ chức nợ nền tảng — số dư khác 0 nghĩa là nền tảng đang mất tiền. */
    public static final AccountCode ORGANIZER_RECEIVABLE = new AccountCode("1320");

    /** Phải trả tổ chức, đang giữ tới sau ngày diễn. */
    public static final AccountCode ORGANIZER_PAYABLE_HELD = new AccountCode("2011");

    /** Phải trả tổ chức, đã đến hạn rút. Số dư tài khoản này CHÍNH LÀ số dư khả dụng. */
    public static final AccountCode ORGANIZER_PAYABLE_AVAILABLE = new AccountCode("2012");

    /** Dự phòng hoàn tiền giữ lại theo tỷ lệ. */
    public static final AccountCode REFUND_RESERVE = new AccountCode("2013");

    /** Phải trả hoàn tiền cho khách. */
    public static final AccountCode REFUND_PAYABLE = new AccountCode("2020");

    /** Tiền đã nhận nhưng chưa xác định được chủ. Mọi đồng vào tài khoản đều phải có bút toán. */
    public static final AccountCode SUSPENSE = new AccountCode("2030");

    /** Chi trả đã giữ chỗ nhưng ngân hàng chưa xác nhận. */
    public static final AccountCode PAYOUT_IN_TRANSIT = new AccountCode("2040");

    /** Doanh thu hoa hồng của nền tảng — chỉ tài khoản này là doanh thu thật. */
    public static final AccountCode COMMISSION_REVENUE = new AccountCode("4010");

    public static final AccountCode PAYMENT_FEE_EXPENSE = new AccountCode("5010");
    public static final AccountCode PAYOUT_FEE_EXPENSE = new AccountCode("5020");

    public AccountCode {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Mã tài khoản phải là 4 chữ số: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
