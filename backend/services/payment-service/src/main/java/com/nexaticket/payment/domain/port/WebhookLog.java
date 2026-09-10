// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.domain.port;

/**
 * Nhật ký mọi lần payOS báo có, kể cả những lần bị từ chối.
 *
 * <p>Ghi nguyên văn, và ghi cả những lần không qua được cửa chữ ký. Khi khách nói "tôi chuyển tiền rồi
 * mà không có vé", đây là chỗ duy nhất trả lời được câu hỏi "payOS có báo về không, và ta đã làm gì với
 * nó". Không có nó thì mọi tranh chấp về tiền kết thúc bằng việc tin lời một bên.
 */
public interface WebhookLog {

    void record(Entry entry);

    /**
     * Một dòng nhật ký.
     *
     * <p>Là một record chứ không phải bảy tham số rời: thứ tự của bảy chuỗi và số cạnh nhau là chỗ sai
     * sẽ biên dịch được mà vẫn ghi lẫn {@code reference} sang cột {@code note}.
     *
     * @param payosOrderCode mã link payOS. Ghi tách khỏi {@code reference} vì nó có mặt kể cả khi
     *     <b>không tìm được intent nào</b> — và đúng những lần đó mới là lúc cần nó nhất.
     * @param reference nội dung chuyển khoản của intent đã khớp; null khi không khớp được intent nào
     * @param outcome kết quả xử lý; phải là một trong các giá trị của {@code ck_webhook_outcome}
     * @param rawJson payload nguyên văn nhận được
     */
    record Entry(
            String provider,
            String providerTxnId,
            Long payosOrderCode,
            String reference,
            Long amountVnd,
            String rawJson,
            String outcome,
            String note) {

        /** Một lần bị chặn ngay ở cửa: chữ ký sai, body không parse được. Chưa có intent nào để nói tới. */
        public static Entry rejected(String provider, String rawJson, String note) {
            return new Entry(provider, null, null, null, null, rawJson, "REJECTED", note);
        }
    }
}
