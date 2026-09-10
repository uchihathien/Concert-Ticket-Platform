// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.port;

/**
 * Một service khác không trả lời được.
 *
 * <p>Tách khỏi mọi ngoại lệ khác vì màn hình tổng hợp phải phân biệt hai thứ trông giống nhau:
 * "không có dữ liệu" (suất chưa lên bán, chưa bán được vé nào) và "không hỏi được". Cái đầu là số
 * 0, cái sau là một khoảng trống — và hiển thị doanh thu 0 đồng vì analytics đang chết là báo sai
 * cho ban tổ chức về chính tiền của họ.
 */
public class UpstreamUnavailableException extends RuntimeException {

    private final String service;

    public UpstreamUnavailableException(String service, String message, Throwable cause) {
        super(message, cause);
        this.service = service;
    }

    public String service() {
        return service;
    }
}
