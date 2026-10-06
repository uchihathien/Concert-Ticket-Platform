// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

/**
 * Biến văn bản thành vector để tìm kiếm ngữ nghĩa.
 *
 * <p><b>Mô hình nhúng và cột vector trong database gắn chặt với nhau.</b> Số chiều phải khớp, và
 * vector của hai mô hình khác nhau <i>không</i> so sánh được — đổi mô hình nghĩa là nhúng lại toàn
 * bộ kho tri thức. Không đổi mà cứ chạy thì truy vấn vẫn trả kết quả, chỉ là kết quả vô nghĩa: một
 * kiểu hỏng không có thông báo lỗi nào cả.
 */
public interface EmbeddingPort {

    /**
     * Nhúng <b>câu hỏi</b>. Tách khỏi {@link #embedDocument(String)} vì nhà cung cấp phân biệt hai
     * loại và chất lượng tìm kiếm phụ thuộc vào việc khai đúng loại.
     */
    float[] embedQuery(String text);

    /** Nhúng <b>tài liệu</b> lúc nạp kho tri thức. */
    float[] embedDocument(String text);

    /** Số chiều mô hình sinh ra — phải khớp cột {@code vector(n)} trong migration. */
    int dimensions();
}
