// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.model;

import java.util.UUID;

/**
 * Tài khoản ký quỹ của nền tảng.
 *
 * <p>Của nền tảng, không của tổ chức: khách chuyển tiền vào đây, không chuyển thẳng cho ban tổ
 * chức (custodial-funds.md). Chỉ {@code SUPER_ADMIN} thấy và sửa (ADR-1010).
 */
public record EscrowBankAccount(
        UUID id,
        String bankBin,
        String bankName,
        String accountNumber,
        String accountName,
        boolean active,
        boolean preferred) {

    public EscrowBankAccount {
        if (!bankBin.matches("[0-9]{6}")) {
            throw new IllegalArgumentException("BIN ngân hàng phải là 6 chữ số: " + bankBin);
        }
        if (!accountNumber.matches("[0-9]{4,20}")) {
            throw new IllegalArgumentException("Số tài khoản không hợp lệ");
        }
    }
}
