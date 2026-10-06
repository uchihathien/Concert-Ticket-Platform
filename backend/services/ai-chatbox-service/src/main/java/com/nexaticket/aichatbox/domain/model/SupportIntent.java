// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.model;

/**
 * Khách đang cần gì — nhãn phân loại của một phiếu chuyển tiếp.
 *
 * <p>Khác {@link HandoffTrigger}, vốn trả lời "vì sao phải chuyển" (khách đòi / trợ lý bỏ cuộc).
 * Ý định trả lời "chuyển để làm gì", và đó là thứ bàn hỗ trợ cần để <b>chia việc</b>: người quen
 * xử lý hoàn tiền nhận phiếu REFUND, người trực sự cố ngày diễn nhận INCIDENT. Không có nhãn này
 * thì hàng đợi là một dãy phiếu giống hệt nhau và ai cũng phải mở từng cái để biết mình có xử lý
 * được không.
 *
 * <p>Giá trị được ghi vào database dưới dạng tên hằng — thêm được, không đổi tên và không xoá.
 */
public enum SupportIntent {

    /** Không xếp được vào nhóm nào cụ thể. Mặc định cho phiếu cũ và cho câu hỏi mơ hồ. */
    GENERAL,

    /** Hỏi về trạng thái, thanh toán, hoặc vé của một đơn đã có. */
    ORDER_STATUS,

    /** Muốn mua hoặc giữ chỗ — trợ lý đã thử hoặc không thể tự đặt được. */
    BOOKING,

    /** Xin hoàn tiền, đổi vé, đổi tên trên vé. */
    REFUND,

    /** Sự cố cụ thể: không nhận được vé, thanh toán rồi mà chưa có, QR không quét được. */
    INCIDENT,

    /** Khiếu nại về dịch vụ, không gắn với một đơn hay một sự cố kỹ thuật rõ ràng. */
    COMPLAINT,

    /** Hỏi về sự kiện: lịch, địa điểm, quy định — thứ trợ lý thường tự trả lời được. */
    EVENT_INFO
}
