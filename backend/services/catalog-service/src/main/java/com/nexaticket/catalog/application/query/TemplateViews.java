// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application.query;

import java.util.List;
import java.util.UUID;

/**
 * DTO của khung concert.
 *
 * <p>Tách khỏi {@link CatalogViews} vì đây là hợp đồng của một màn hình khác hẳn — khu vực quản trị
 * của Tổng công ty — và nó đổi theo nhịp riêng. Gom vào cùng file với DTO của trang khách sẽ khiến
 * mỗi lần rà lại hợp đồng công khai phải đọc lẫn cả những thứ chỉ nền tảng thấy.
 */
public final class TemplateViews {

    private TemplateViews() {}

    /**
     * Một dòng trong danh sách khung.
     *
     * @param capacity tổng sức chứa của khung — con số ban tổ chức dùng để chọn, nên nó phải có mặt
     *     ngay ở danh sách chứ không chỉ ở trang chi tiết
     */
    public record TemplateRow(
            UUID id,
            String code,
            String name,
            String category,
            String description,
            String status,
            int zoneCount,
            int capacity) {}

    /**
     * Chi tiết một khung, đủ để dựng cả màn hình sửa sơ đồ.
     *
     * @param stage {@code null} nghĩa là khung chưa khai sân khấu và dùng sân khấu mặc định. Giữ
     *     {@code null} thay vì giải sẵn vì đây là DTO của màn hình <b>sửa</b>, và gửi bản đã giải
     *     ngược lại qua {@code PUT} sẽ biến mọi khung thành đã-khai sau một lần lưu.
     */
    public record TemplateDetail(
            UUID id,
            String code,
            String name,
            String category,
            String description,
            String status,
            int capacity,
            CatalogViews.AdminStage stage,
            List<TemplateZoneView> zones) {}

    /**
     * @param seatCount số chỗ bán được của khu, đã tính sẵn {@code rowCount × seatsPerRow} cho khu
     *     ngồi — frontend không phải biết công thức, và không có bản sao thứ hai của nó để lệch
     * @param suggestedPriceVnd {@code null} nghĩa là khung không gợi ý giá và tổ chức bắt buộc khai
     * @param layout {@code null} nghĩa là khu chưa đặt vị trí và bố cục tự động xếp nó. Dùng lại
     *     kiểu của {@link CatalogViews} chứ không dựng kiểu song song: khung và địa điểm tả hình
     *     học bằng cùng một ngôn ngữ, và đó chính là điều kiện để áp khung chỉ là một phép chép.
     */
    public record TemplateZoneView(
            UUID id,
            String zoneCode,
            String name,
            String kind,
            Integer rowCount,
            Integer seatsPerRow,
            Integer capacity,
            int seatCount,
            int sortOrder,
            Long suggestedPriceVnd,
            CatalogViews.AdminZoneLayout layout) {}
}
