// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Quy định của một sự kiện như <b>người soạn</b> nhìn nó — kèm cả bản nháp.
 *
 * <p>Tách khỏi {@link EventRules} vì hai bên trả lời hai câu hỏi khác nhau. {@link EventRules} là
 * thứ tool {@code getEventRules} đọc, và nó chỉ được thấy bản <b>đã công bố</b>; thêm cờ
 * {@code published} vào đó là mời một lời gọi quên kiểm cờ và đọc bản nháp ra cho khách. Ở đây thì
 * ngược lại: người soạn phải thấy bản nháp, nếu không họ không có cách nào soạn.
 */
public record RulesEntry(UUID eventId, String eventTitle, String content, boolean published, Instant updatedAt) {}
