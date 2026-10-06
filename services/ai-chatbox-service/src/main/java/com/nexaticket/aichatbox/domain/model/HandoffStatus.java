// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

/** Vòng đời một phiếu chuyển tiếp sang người thật. */
public enum HandoffStatus {

    /** Đang xếp hàng, chưa ai nhận. */
    WAITING,

    /** Một người trực đã nhận và đang trả lời. */
    ASSIGNED,

    /**
     * Đã xong.
     *
     * <p>Phiếu không bị xoá: "phiên này đã phải nhờ người 4 lần" là đúng thứ cần để biết trợ lý
     * đang hụt ở đâu.
     */
    RESOLVED;

    /** Phiếu còn mở thì trợ lý AI không trả lời nữa — người thật đang cầm cuộc hội thoại. */
    public boolean isOpen() {
        return this != RESOLVED;
    }
}
