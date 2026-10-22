// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

/**
 * Đã có một đoạn tri thức <b>chung</b> ({@code eventId = null}) mang đúng tiêu đề này.
 *
 * <p>Tiêu đề là khoá của tri thức chung: seeder nạp tri thức nền theo những tiêu đề còn thiếu, và
 * index {@code uq_knowledge_global_title} chặn bản trùng kể cả khi hai replica cùng nạp.
 */
public class KnowledgeTitleTakenException extends RuntimeException {

    public KnowledgeTitleTakenException(String title) {
        super("Đã có đoạn tri thức chung mang tiêu đề '" + title + "'");
    }
}
