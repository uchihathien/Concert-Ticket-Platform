// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.catalog.infrastructure.persistence;

import com.nexaticket.catalog.domain.model.LayoutShape;
import com.nexaticket.catalog.domain.model.StageArea;
import com.nexaticket.catalog.domain.model.StageShape;
import com.nexaticket.catalog.domain.model.ZoneLayout;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Đọc/ghi các cột hình học, dùng chung cho khu của địa điểm và khu của khung.
 *
 * <p>Hai bảng có <b>đúng cùng</b> bảy cột bố cục và hai bảng cha có đúng cùng năm cột sân khấu —
 * đó là điều kiện để áp khung chỉ là một phép chép. Viết hai bản mapper song song nghĩa là lần sửa
 * thứ ba chúng sẽ lệch nhau, và triệu chứng sẽ là "khung sân khấu tròn áp xuống thành chữ nhật".
 */
final class LayoutColumns {

    /** Thứ tự cột phải khớp {@link #zoneParams} — hai thứ luôn được dùng trong cùng một câu lệnh. */
    static final String ZONE_COLUMNS = "layout_shape, layout_origin_x, layout_origin_y, layout_rotation_deg, "
            + "layout_inner_radius, layout_start_angle_deg, layout_end_angle_deg";

    /** Thứ tự cột phải khớp {@link #stageParams}. */
    static final String STAGE_COLUMNS = "stage_shape, stage_x, stage_y, stage_width, stage_height";

    private LayoutColumns() {}

    /** Bảy tham số của một khu, theo đúng thứ tự {@link #ZONE_COLUMNS}. Khu chưa đặt thì toàn NULL. */
    static Object[] zoneParams(ZoneLayout layout) {
        if (layout == null) {
            return new Object[] {null, null, null, null, null, null, null};
        }
        boolean arc = layout.shape() == LayoutShape.ARC;
        return new Object[] {
            layout.shape().name(),
            layout.originX(),
            layout.originY(),
            // Cột chỉ có nghĩa với hình còn lại được ghi NULL chứ không ghi 0: đọc lên, NULL nói
            // "hình này không dùng cột ấy", còn 0 nói "dùng, và bằng 0" — một bán kính 0.
            arc ? null : layout.rotationDeg(),
            arc ? layout.innerRadius() : null,
            arc ? layout.startAngleDeg() : null,
            arc ? layout.endAngleDeg() : null
        };
    }

    static ZoneLayout readZone(ResultSet rs) throws SQLException {
        String shape = rs.getString("layout_shape");
        if (shape == null) {
            return null;
        }
        return new ZoneLayout(
                LayoutShape.valueOf(shape),
                rs.getDouble("layout_origin_x"),
                rs.getDouble("layout_origin_y"),
                rs.getDouble("layout_rotation_deg"),
                rs.getDouble("layout_inner_radius"),
                rs.getDouble("layout_start_angle_deg"),
                rs.getDouble("layout_end_angle_deg"));
    }

    /** Năm tham số sân khấu, theo đúng thứ tự {@link #STAGE_COLUMNS}. */
    static Object[] stageParams(StageArea stage) {
        if (stage == null) {
            return new Object[] {null, null, null, null, null};
        }
        return new Object[] {
            stage.shape().name(),
            stage.x(),
            stage.y(),
            stage.width(),
            stage.shape() == StageShape.CIRCLE ? null : stage.height()
        };
    }

    static StageArea readStage(ResultSet rs) throws SQLException {
        String shape = rs.getString("stage_shape");
        if (shape == null) {
            return null;
        }
        return new StageArea(
                StageShape.valueOf(shape),
                rs.getDouble("stage_x"),
                rs.getDouble("stage_y"),
                rs.getDouble("stage_width"),
                rs.getDouble("stage_height"));
    }
}
