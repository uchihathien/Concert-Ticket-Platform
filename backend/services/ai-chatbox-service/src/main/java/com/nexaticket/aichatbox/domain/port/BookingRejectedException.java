// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.domain.port;

/**
 * Inventory hoặc ordering <b>từ chối</b> một yêu cầu đặt vé vì lý do nghiệp vụ — hết chỗ, vượt hạn
 * mức mua, suất đã đóng bán, chỗ giữ đã hết hạn.
 *
 * <p>Khác {@link RemoteCallException} ở chỗ quan trọng nhất: service bên kia <i>đã trả lời</i>, và
 * câu trả lời là "không". Với khách thì đó là thông tin ("khu này hết vé rồi"), không phải sự cố
 * ("hệ thống đang bận") — và nói nhầm cái này thành cái kia là mời khách chờ một thứ sẽ không đổi.
 *
 * @param reason câu dành cho khách, viết bằng tiếng Việt, do adapter dịch từ mã lỗi của service
 */
public class BookingRejectedException extends RuntimeException {

    private final String reason;

    public BookingRejectedException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
