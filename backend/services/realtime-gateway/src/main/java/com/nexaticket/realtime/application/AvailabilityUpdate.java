// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.realtime.application;

import java.util.UUID;

/**
 * Một thông báo "tồn kho của suất diễn này đã đổi".
 *
 * <p>Cố ý <b>không</b> mang danh sách ghế nào đổi. Client chỉ cần biết version đã nhảy rồi tự
 * fetch lại sơ đồ — gửi kèm chi tiết sẽ biến mỗi lần giữ chỗ thành một message lớn, nhân với hàng
 * nghìn kết nối đang mở.
 */
public record AvailabilityUpdate(UUID eventSessionId, long version) {}
