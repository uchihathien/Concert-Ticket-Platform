// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application;

import com.nexaticket.platform.web.error.ErrorCode;

/** Mã lỗi nghiệp vụ của catalog. Hợp đồng ổn định — không đổi, không xoá mã đã phát hành. */
public enum CatalogErrorCode implements ErrorCode {
    VENUE_NOT_FOUND(404),
    ZONE_NOT_FOUND(404),
    EVENT_NOT_FOUND(404),
    SESSION_NOT_FOUND(404),
    TICKET_TYPE_NOT_FOUND(404),
    SLUG_ALREADY_TAKEN(409),
    ZONE_ALREADY_PRICED(409),
    /** Còn vướng mắc chưa gỡ; danh sách nằm ở {@code meta.blockers}. */
    PUBLISH_BLOCKED(409),
    EVENT_NOT_PUBLISHED(409),
    INVALID_EVENT_STATE(409),

    // --- Khung concert của nền tảng --------------------------------------

    TEMPLATE_NOT_FOUND(404),
    TEMPLATE_CODE_TAKEN(409),
    /** Khung chưa ACTIVE hoặc đã lưu trữ: tổ chức không dựng sự kiện mới từ nó được. */
    TEMPLATE_NOT_USABLE(409),
    /** Khung chưa khai khu nào nên chưa mở cho tổ chức dùng được. */
    TEMPLATE_WITHOUT_ZONE(409),
    /** Đã có địa điểm dựng từ khung này — lưu trữ được, xoá thì không. */
    TEMPLATE_IN_USE(409),

    // --- Sơ đồ khu của tổ chức -------------------------------------------

    /**
     * Sơ đồ đến từ khung của nền tảng: khu vực là kết cấu cố định, không sửa qua đường này.
     *
     * <p>409 chứ không 403: người gọi có đủ quyền, chỉ là đối tượng ở trạng thái không cho phép
     * thao tác. 403 sẽ khiến ban tổ chức đi xin cấp thêm quyền cho một việc mà không quyền nào mở
     * được.
     */
    VENUE_LAYOUT_LOCKED(409),
    /** Địa điểm đã có sự kiện từng lên bán: tồn kho bên Inventory dựng từ đúng những mã khu này. */
    VENUE_IN_USE(409),
    /** Khung không gợi ý giá cho khu này và request cũng không khai — không có giá thì không bán được. */
    ZONE_PRICE_REQUIRED(422),

    /**
     * Mã khuyến mãi không dùng được cho suất diễn này.
     *
     * <p>422 chứ không phải 400: request đúng cú pháp, chỉ là nội dung không thoả điều kiện nghiệp
     * vụ. Phân biệt đó quan trọng với ordering-service — nó dùng chính mã này để biết đây là câu
     * trả lời dứt khoát của Catalog, khác hẳn một cái 404 vì gọi nhầm đường dẫn.
     */
    PROMOTION_INVALID(422);

    private final int status;

    CatalogErrorCode(int status) {
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
