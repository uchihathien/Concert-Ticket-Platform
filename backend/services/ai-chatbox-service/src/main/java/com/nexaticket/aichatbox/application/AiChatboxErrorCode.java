// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import com.nexaticket.platform.web.error.ErrorCode;

/** Mã lỗi nghiệp vụ của ai-chatbox. Hợp đồng ổn định — không đổi, không xoá mã đã phát hành. */
public enum AiChatboxErrorCode implements ErrorCode {

    /** Phiên chat không tồn tại hoặc thuộc về người khác. Cố ý không phân biệt hai trường hợp. */
    CHAT_SESSION_NOT_FOUND(404),

    /**
     * Không lấy được câu trả lời từ mô hình.
     *
     * <p>503 chứ không phải 500: nhà cung cấp quá tải là chuyện thường xuyên và tạm thời, client
     * nên hiện "thử lại" chứ không phải "đã có lỗi xảy ra".
     */
    ASSISTANT_UNAVAILABLE(503),

    // --- Chuyển tiếp sang người thật --------------------------------------

    HANDOFF_NOT_FOUND(404),

    /**
     * Người trực khác vừa nhận phiếu này.
     *
     * <p>409 chứ không 403: người gọi có đủ quyền, chỉ là đối tượng đã đổi trạng thái. Màn hình
     * cần phân biệt để gỡ phiếu khỏi danh sách và mở phiếu khác, thay vì hiện "bạn không có quyền"
     * cho một người vừa chậm nửa giây.
     */
    HANDOFF_ALREADY_TAKEN(409),

    /** Chưa nhận phiếu mà đã trả lời. Không có chốt này thì {@code assignedAgentId} là cột trang trí. */
    HANDOFF_NOT_ASSIGNED(409),

    /**
     * Phiếu đã đóng.
     *
     * <p>Tách khỏi {@link #HANDOFF_NOT_ASSIGNED} vì màn hình phải làm hai việc khác nhau: "chưa
     * nhận phiếu" thì hiện nút Nhận, còn "đã đóng" thì đóng luôn khung trả lời — từ lượt kế tiếp
     * trợ lý AI đã nói trở lại, và một câu của người trực chen vào lúc đó là hai phía cùng trả lời.
     */
    HANDOFF_ALREADY_RESOLVED(409),

    // --- Kho tri thức ------------------------------------------------------

    KNOWLEDGE_CHUNK_NOT_FOUND(404),

    EVENT_RULES_NOT_FOUND(404),

    /**
     * Không nhúng được văn bản lúc soạn kho tri thức.
     *
     * <p>503 chứ không 500, và cố ý <b>khác</b> hẳn cách đường đọc xử lý cùng sự cố: lúc trả lời
     * khách, mất embedding chỉ làm trợ lý kém thông tin nên nó đi tiếp không kèm tri thức nền. Lúc
     * ghi thì không có lựa chọn ấy — một đoạn không có vector là một đoạn không bao giờ tìm ra
     * được, nên người soạn phải biết ngay lúc bấm Lưu.
     */
    KNOWLEDGE_EMBEDDING_FAILED(503);

    private final int status;

    AiChatboxErrorCode(int status) {
        this.status = status;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int httpStatus() {
        return status;
    }
}
