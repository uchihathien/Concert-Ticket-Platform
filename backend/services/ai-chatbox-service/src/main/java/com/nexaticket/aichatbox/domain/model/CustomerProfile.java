// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

/**
 * Hồ sơ của chính người đang chat, như identity-service trả về cho {@code GET /v1/me}.
 *
 * <p>Chỉ ba trường. Không có id tổ chức, không có cờ quản trị: trợ lý trả lời khách về đơn hàng của
 * họ, và những trường ấy không trả lời được câu hỏi nào mà lại là thứ không nên lọt vào prompt.
 *
 * <p>Email và số điện thoại ở đây là <b>bản đầy đủ</b>; việc che bớt trước khi đưa cho mô hình là
 * quyết định của tầng application — xem {@code ToolDispatcher}.
 */
public record CustomerProfile(String fullName, String email, String phone) {}
