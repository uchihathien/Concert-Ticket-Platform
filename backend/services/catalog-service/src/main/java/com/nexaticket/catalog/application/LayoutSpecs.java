// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.application;

import com.nexaticket.catalog.domain.model.LayoutShape;
import com.nexaticket.catalog.domain.model.StageArea;
import com.nexaticket.catalog.domain.model.StageShape;
import com.nexaticket.catalog.domain.model.ZoneLayout;
import com.nexaticket.platform.web.error.ApiException;

/**
 * Dịch hình học từ hình dạng của request sang hình dạng của domain.
 *
 * <p>Dùng chung cho <b>hai</b> đường khai sơ đồ — khu của địa điểm và khu của khung — vì chúng nhận
 * đúng cùng bảy con số. Đó không phải trùng hợp: điều kiện để áp khung chỉ là một phép chép chính
 * là việc khung và địa điểm tả hình học bằng cùng một ngôn ngữ. Hai bản dịch song song sẽ lệch
 * nhau, và triệu chứng là một khung sân khấu tròn áp xuống thành mấy khối chữ nhật.
 *
 * <p>Mọi enum ở đây là {@link String}: tầng interfaces không được chạm vào domain
 * (ArchitectureRules.hexagonalLayers), nên record nào đi từ controller xuống cũng phải dựng được
 * mà không nhìn thấy {@link LayoutShape}.
 */
public final class LayoutSpecs {

    private LayoutSpecs() {}

    /**
     * Bố cục của một khu, như request gửi lên.
     *
     * @param shape {@code null} nghĩa là "chưa đặt vị trí" và bố cục tự động sẽ xếp khu này — khác
     *     hẳn "đặt đúng chỗ mặc định"
     */
    public record ZoneLayoutSpec(
            String shape,
            Double originX,
            Double originY,
            Double rotationDeg,
            Double innerRadius,
            Double startAngleDeg,
            Double endAngleDeg) {}

    /** Sân khấu, như request gửi lên. {@code null} đưa địa điểm về sân khấu mặc định. */
    public record StageSpec(String shape, Double x, Double y, Double width, Double height) {}

    /**
     * Toạ độ thiếu thì coi như chưa đặt, không phải lỗi.
     *
     * <p>Bỏ qua phần toạ độ khai dở là hành vi đúng ở đây: form sơ đồ gửi cả tập khu, và một khu
     * chưa kéo vào vị trí nào là trạng thái bình thường của nó. Từ chối cả lệnh vì một khu chưa
     * đặt nghĩa là ban tổ chức không lưu được sơ đồ cho tới khi xếp xong từng khu một.
     */
    public static ZoneLayout toLayout(ZoneLayoutSpec spec) {
        if (spec == null || spec.shape() == null || spec.originX() == null || spec.originY() == null) {
            return null;
        }
        LayoutShape shape = parseEnum(LayoutShape.class, spec.shape(), "Hình dạng bố cục không hợp lệ: ");
        return guarded(() -> shape == LayoutShape.ARC
                ? ZoneLayout.arc(
                        spec.originX(),
                        spec.originY(),
                        orZero(spec.innerRadius()),
                        orZero(spec.startAngleDeg()),
                        orZero(spec.endAngleDeg()))
                : ZoneLayout.grid(spec.originX(), spec.originY(), orZero(spec.rotationDeg())));
    }

    public static StageArea toStage(StageSpec spec) {
        if (spec == null || spec.shape() == null) {
            return null;
        }
        StageShape shape = parseEnum(StageShape.class, spec.shape(), "Hình dạng sân khấu không hợp lệ: ");
        return guarded(() ->
                new StageArea(shape, orZero(spec.x()), orZero(spec.y()), orZero(spec.width()), orZero(spec.height())));
    }

    /**
     * Ràng buộc hình học của domain là luật nghiệp vụ, nên người gọi phải thấy nó dưới dạng 4xx có
     * mã — không phải 500.
     *
     * <p>"Bán kính phải > 0" và "cung phải quét xuôi" là những câu màn hình sơ đồ hiện được cho
     * người dùng. Để {@link IllegalArgumentException} rơi ra ngoài thì cùng thông tin ấy đến nơi
     * dưới dạng "Internal Server Error", và người dùng không biết mình phải sửa gì.
     */
    private static <T> T guarded(java.util.function.Supplier<T> build) {
        try {
            return build.get();
        } catch (IllegalArgumentException e) {
            throw new ApiException(CatalogErrorCode.ZONE_LAYOUT_INVALID, e.getMessage());
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String message) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(CatalogErrorCode.ZONE_LAYOUT_INVALID, message + value);
        }
    }

    private static double orZero(Double value) {
        return value == null ? 0 : value;
    }
}
