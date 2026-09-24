// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.domain.model;

/**
 * Sân khấu trên mặt bằng: hình dạng và chỗ đứng của nó.
 *
 * <p>Cùng đơn vị với {@link ZoneLayout} — một đơn vị là khoảng cách giữa hai ghế. Quy ước hướng:
 * sân khấu ở phía <b>trên</b> ({@code y} âm), khán giả ở phía dưới ({@code y} dương). Quy ước này
 * là thứ khiến "hàng 1" luôn là hàng gần sân khấu ở mọi khán phòng, kể cả khán phòng tròn.
 *
 * @param x tâm sân khấu theo trục ngang
 * @param y tâm sân khấu theo trục dọc
 * @param width bề ngang; với {@link StageShape#CIRCLE} đây là đường kính và {@code height} bị bỏ qua
 */
public record StageArea(StageShape shape, double x, double y, double width, double height) {

    /**
     * Sân khấu mặc định khi tổ chức chưa đặt gì: hộp chữ nhật rộng 24 ghế, nằm ngay trên hàng đầu.
     *
     * <p>Có một sân khấu mặc định thay vì không có gì, vì một sơ đồ không sân khấu không nói được
     * hướng nhìn — và tổ chức nào cũng có sân khấu, chỉ là không phải ai cũng muốn khai toạ độ của
     * nó.
     */
    public static final StageArea DEFAULT = new StageArea(StageShape.RECTANGLE, 0, -7, 24, 4);

    public StageArea {
        if (shape == null) {
            throw new IllegalArgumentException("Sân khấu phải có hình dạng");
        }
        if (width <= 0) {
            throw new IllegalArgumentException("Sân khấu phải có bề ngang > 0");
        }
        if (shape != StageShape.CIRCLE && height <= 0) {
            throw new IllegalArgumentException("Sân khấu phải có chiều sâu > 0");
        }
    }

    /** Bao hình của sân khấu, để tính {@code viewBox} chung với khán đài. */
    public double minX() {
        return x - width / 2;
    }

    public double maxX() {
        return x + width / 2;
    }

    public double minY() {
        return y - effectiveHeight() / 2;
    }

    public double maxY() {
        return y + effectiveHeight() / 2;
    }

    /** Sân khấu tròn cao bằng đường kính của nó; hai hình còn lại dùng chiều sâu đã khai. */
    public double effectiveHeight() {
        return shape == StageShape.CIRCLE ? width : height;
    }
}
