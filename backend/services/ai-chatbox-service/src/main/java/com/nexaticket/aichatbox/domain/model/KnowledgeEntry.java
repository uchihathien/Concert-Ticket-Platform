// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Một đoạn tri thức như <b>người soạn</b> nhìn nó.
 *
 * <p>Khác {@link KnowledgeChunk} đúng ở một chỗ, và chỗ đó quan trọng: không có {@code distance}.
 * Khoảng cách chỉ có nghĩa khi so với một câu hỏi cụ thể; trong một danh mục để rà soát thì nó là
 * số không có nghĩa, và một trường luôn bằng 0 sẽ được ai đó đọc như "trùng khớp hoàn hảo".
 *
 * @param eventId {@code null} nghĩa là tri thức chung của nền tảng, không thuộc sự kiện nào
 */
public record KnowledgeEntry(UUID id, UUID eventId, String title, String content, Instant createdAt) {}
