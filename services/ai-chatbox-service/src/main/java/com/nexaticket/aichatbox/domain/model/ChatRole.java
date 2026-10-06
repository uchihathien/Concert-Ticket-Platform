// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

/**
 * Ai nói.
 *
 * <p>Không có vai "system": prompt hệ thống không phải một lượt hội thoại và không lưu.
 *
 * <p>{@link #AGENT} tách khỏi {@link #ASSISTANT} một cách có chủ đích. Khách phải biết mình đang
 * nói với máy hay với người — một dòng chat không phân biệt được hai thứ đó là một dòng chat nói
 * dối. Với mô hình thì cả hai đều là "lượt của phía hỗ trợ", nên khi hội thoại quay lại tay trợ lý
 * AI, {@code AGENT} được nạp vào prompt như một lượt trợ lý.
 */
public enum ChatRole {
    USER,
    ASSISTANT,
    AGENT
}
