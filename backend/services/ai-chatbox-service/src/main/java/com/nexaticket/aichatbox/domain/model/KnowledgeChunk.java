// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.util.UUID;

/**
 * Một đoạn tri thức lấy từ tìm kiếm vector.
 *
 * @param distance khoảng cách cosine — <b>nhỏ hơn là gần hơn</b>. Giữ lại con số này thay vì chỉ
 *     trả danh sách đã sắp xếp, vì tầng application cần ngưỡng để loại đoạn không liên quan: pgvector
 *     luôn trả đủ {@code topK} kết quả kể cả khi kho tri thức chẳng có gì dính tới câu hỏi, và một
 *     đoạn lạc đề đưa vào prompt là nguyên liệu để mô hình bịa.
 */
public record KnowledgeChunk(UUID id, UUID eventId, String title, String content, double distance) {}
