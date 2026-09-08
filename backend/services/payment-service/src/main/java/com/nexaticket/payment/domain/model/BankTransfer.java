// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.model;

/**
 * Một giao dịch chuyển khoản đến, <b>đã dịch sang ngôn ngữ của ta</b>.
 *
 * <p>Không trường nào ở đây mang tên theo SePay. Đó là điểm của Anti-Corruption Layer: đổi nhà
 * cung cấp webhook chỉ phải viết một translator mới, còn ma trận phân loại — phần chứa toàn bộ
 * luật nghiệp vụ về tiền — không đổi một dòng.
 *
 * @param providerEventId mã giao dịch của nhà cung cấp, dùng để chống xử lý trùng
 * @param rawContent ô "nội dung chuyển khoản" nguyên văn, chưa bóc mã tham chiếu
 * @param amountVnd số tiền thực nhận
 * @param receivingBankBin BIN tài khoản NHẬN — phải khớp snapshot trên mã QR
 * @param receivingAccountNumber số tài khoản nhận
 */
public record BankTransfer(
        String providerEventId,
        String rawContent,
        long amountVnd,
        String receivingBankBin,
        String receivingAccountNumber) {

    public BankTransfer {
        if (providerEventId == null || providerEventId.isBlank()) {
            throw new IllegalArgumentException("Thiếu mã giao dịch của nhà cung cấp");
        }
    }

    /**
     * Tiền vào (dương) hay tiền ra (âm/không).
     *
     * <p>SePay gửi cả giao dịch ghi nợ. Chỉ tiền vào mới có thể là thanh toán của khách; giao dịch
     * tiền ra là thứ duy nhất ta dám kết luận {@code REJECTED} mà không sợ bỏ rơi tiền của ai.
     */
    public boolean isIncoming() {
        return amountVnd > 0;
    }
}
