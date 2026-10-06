// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

import java.time.Instant;

/**
 * Một dòng trong hội thoại, như <b>màn hình</b> đọc nó.
 *
 * <p>Khác {@link Exchange}, vốn là hình dạng mà <b>mô hình</b> đọc. Hai kiểu cho cùng dữ liệu là
 * có chủ đích: prompt cần gộp AGENT và ASSISTANT lại làm một ("phía hỗ trợ đã nói"), còn giao diện
 * thì bắt buộc phải tách — khách phải biết mình đang nói với máy hay với người.
 */
public record ChatMessage(ChatRole role, String content, Instant at) {}
