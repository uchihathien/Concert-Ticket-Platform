// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.model;

/**
 * Dựng payload VietQR theo chuẩn EMVCo QR Code Specification for Payment Systems.
 *
 * <p>Trả về <b>chuỗi</b>, không phải ảnh: frontend tự render QR. Nhẹ hơn nhiều so với sinh và
 * truyền ảnh, và quan trọng hơn là không phụ thuộc một dịch vụ sinh QR bên ngoài — thứ sẽ trở
 * thành điểm chết chung cho toàn bộ luồng thanh toán.
 *
 * <h2>Cấu trúc TLV</h2>
 *
 * <p>Mỗi trường là {@code ID(2) + độ dài(2) + giá trị}. Độ dài luôn hai chữ số có số 0 ở đầu, nên
 * giá trị dài quá 99 ký tự là không biểu diễn được — tên tài khoản dài bị cắt chứ không được để
 * tràn, vì tràn sẽ làm lệch toàn bộ phần sau và mã trở thành rác.
 *
 * <pre>
 *   00 Payload Format Indicator      = "01"
 *   01 Point of Initiation Method    = "12"  (động: có số tiền, dùng một lần)
 *   38 Merchant Account Information
 *      00 GUID                       = "A000000727"   (NAPAS)
 *      01 Beneficiary Organization
 *         00 Acquirer ID / BIN ngân hàng (6 số)
 *         01 Số tài khoản
 *      02 Service code               = "QRIBFTTA"     (chuyển tới TÀI KHOẢN)
 *   53 Transaction Currency          = "704"          (VND, ISO 4217)
 *   54 Transaction Amount
 *   58 Country Code                  = "VN"
 *   62 Additional Data
 *      08 Purpose of Transaction     = mã tham chiếu
 *   63 CRC                           = CRC-16/CCITT-FALSE
 * </pre>
 *
 * <p>Số tiền là chuỗi <b>số nguyên VND, không phần thập phân</b>: VND không có đơn vị nhỏ hơn,
 * và ghi ".00" khiến một số app đọc thành số tiền khác.
 */
public final class VietQr {

    private static final String NAPAS_GUID = "A000000727";
    private static final String SERVICE_TRANSFER_TO_ACCOUNT = "QRIBFTTA";
    private static final String CURRENCY_VND = "704";
    private static final String COUNTRY_VN = "VN";

    /** ID của trường CRC, cộng với độ dài "04" — phải nằm trong dữ liệu được tính CRC. */
    private static final String CRC_TAG = "6304";

    private VietQr() {}

    /**
     * @param bankBin mã BIN 6 số của ngân hàng nhận
     * @param accountNumber số tài khoản nhận
     * @param amountVnd số tiền, đồng
     * @param reference nội dung chuyển khoản
     */
    public static String build(String bankBin, String accountNumber, long amountVnd, PaymentReference reference) {
        if (amountVnd <= 0) {
            throw new IllegalArgumentException("Số tiền phải dương: " + amountVnd);
        }
        String beneficiary = tlv("00", bankBin) + tlv("01", accountNumber);
        String merchantAccount =
                tlv("00", NAPAS_GUID) + tlv("01", beneficiary) + tlv("02", SERVICE_TRANSFER_TO_ACCOUNT);

        String payload = tlv("00", "01")
                + tlv("01", "12")
                + tlv("38", merchantAccount)
                + tlv("53", CURRENCY_VND)
                + tlv("54", String.valueOf(amountVnd))
                + tlv("58", COUNTRY_VN)
                + tlv("62", tlv("08", reference.value()));

        // CRC được tính TRÊN CẢ "6304" chứ không phải trên phần trước nó. Bỏ sót bốn ký tự này
        // là lỗi phổ biến nhất khi tự dựng VietQR, và nó cho ra một mã trông hoàn toàn hợp lệ.
        String withCrcTag = payload + CRC_TAG;
        return withCrcTag + Crc16.ccittFalse(withCrcTag);
    }

    private static String tlv(String id, String value) {
        if (value.length() > 99) {
            throw new IllegalArgumentException(
                    "Giá trị TLV dài quá 99 ký tự nên không mã hoá được độ dài hai chữ số: " + id);
        }
        return id + String.format("%02d", value.length()) + value;
    }
}
