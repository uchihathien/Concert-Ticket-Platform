-- Bịt lỗ NULL trong ràng buộc hình học của V0102.
--
-- V0102 kiểm bán kính và kích thước sân khấu bằng phép so sánh (`layout_inner_radius > 0`), và
-- một phép so sánh với NULL cho ra NULL chứ không cho ra sai. CHECK trong SQL **đi qua** khi biểu
-- thức bằng NULL — nó chỉ chặn khi biểu thức sai hẳn. Nên một khu ARC thiếu bán kính, hay một sân
-- khấu chữ nhật thiếu chiều cao, vẫn lưu được.
--
-- Vì sao điều đó nguy hiểm chứ không chỉ luộm thuộm: `rs.getDouble` trả 0.0 cho cột NULL mà không
-- báo gì, rồi `ZoneLayout` từ chối bán kính 0 bằng IllegalArgumentException — ném ra giữa đường
-- ĐỌC. Hậu quả không phải một lần ghi hỏng mà là sơ đồ mặt bằng của địa điểm ấy trả 500 mãi mãi,
-- kể cả với người chỉ vào xem. Một hàng sai làm chết một trang.
--
-- Những cột đã có `IS NOT NULL` trong V0102 (origin, góc bắt đầu/kết thúc, stage_x, stage_y) vốn
-- đã kín: `IS NOT NULL` trả sai chứ không trả NULL. Chỉ các cột kiểm bằng so sánh mới hở.

-- Dọn trước khi siết. Đặt cả bố cục về NULL chứ không cố đoán giá trị còn thiếu: NULL nghĩa là
-- "xếp tự động", và FloorPlan.of xử lý được — khu vẫn hiện ra, chỉ là ở chỗ hệ thống tự chọn.
-- Đoán một bán kính thì tạo ra một sơ đồ trông đúng nhưng sai chỗ, và không ai biết để sửa.
UPDATE venue_zones
SET layout_shape = NULL,
    layout_origin_x = NULL,
    layout_origin_y = NULL,
    layout_rotation_deg = NULL,
    layout_inner_radius = NULL,
    layout_start_angle_deg = NULL,
    layout_end_angle_deg = NULL
WHERE layout_shape = 'ARC'
  AND (layout_inner_radius IS NULL OR layout_inner_radius <= 0);

UPDATE concert_template_zones
SET layout_shape = NULL,
    layout_origin_x = NULL,
    layout_origin_y = NULL,
    layout_rotation_deg = NULL,
    layout_inner_radius = NULL,
    layout_start_angle_deg = NULL,
    layout_end_angle_deg = NULL
WHERE layout_shape = 'ARC'
  AND (layout_inner_radius IS NULL OR layout_inner_radius <= 0);

UPDATE venues
SET stage_shape = NULL, stage_x = NULL, stage_y = NULL, stage_width = NULL, stage_height = NULL
WHERE stage_shape IS NOT NULL
  AND (stage_width IS NULL OR (stage_shape <> 'CIRCLE' AND stage_height IS NULL));

UPDATE concert_templates
SET stage_shape = NULL, stage_x = NULL, stage_y = NULL, stage_width = NULL, stage_height = NULL
WHERE stage_shape IS NOT NULL
  AND (stage_width IS NULL OR (stage_shape <> 'CIRCLE' AND stage_height IS NULL));

-- Ràng buộc viết lại. Vẫn nhắc lại nguyên văn hai lần thay vì gọi một hàm dùng chung, vì lý do
-- của V0102 không đổi: pg_dump phục hồi bảng trước hàm, nên một CHECK gọi hàm làm bản dump không
-- nạp lại được.

ALTER TABLE venue_zones DROP CONSTRAINT ck_zone_layout;
ALTER TABLE venue_zones ADD CONSTRAINT ck_zone_layout CHECK (
    layout_shape IS NULL
    OR (layout_shape = 'GRID'
        AND layout_origin_x IS NOT NULL
        AND layout_origin_y IS NOT NULL)
    OR (layout_shape = 'ARC'
        AND layout_origin_x IS NOT NULL
        AND layout_origin_y IS NOT NULL
        AND layout_inner_radius IS NOT NULL AND layout_inner_radius > 0
        AND layout_start_angle_deg IS NOT NULL
        AND layout_end_angle_deg IS NOT NULL
        AND layout_end_angle_deg > layout_start_angle_deg
        AND layout_end_angle_deg - layout_start_angle_deg <= 360)
);

ALTER TABLE concert_template_zones DROP CONSTRAINT ck_template_zone_layout;
ALTER TABLE concert_template_zones ADD CONSTRAINT ck_template_zone_layout CHECK (
    layout_shape IS NULL
    OR (layout_shape = 'GRID'
        AND layout_origin_x IS NOT NULL
        AND layout_origin_y IS NOT NULL)
    OR (layout_shape = 'ARC'
        AND layout_origin_x IS NOT NULL
        AND layout_origin_y IS NOT NULL
        AND layout_inner_radius IS NOT NULL AND layout_inner_radius > 0
        AND layout_start_angle_deg IS NOT NULL
        AND layout_end_angle_deg IS NOT NULL
        AND layout_end_angle_deg > layout_start_angle_deg
        AND layout_end_angle_deg - layout_start_angle_deg <= 360)
);

ALTER TABLE venues DROP CONSTRAINT ck_venue_stage;
ALTER TABLE venues ADD CONSTRAINT ck_venue_stage CHECK (
    stage_shape IS NULL
    OR (stage_shape IN ('RECTANGLE', 'CIRCLE', 'THRUST')
        AND stage_x IS NOT NULL
        AND stage_y IS NOT NULL
        AND stage_width IS NOT NULL AND stage_width > 0
        -- Sân khấu tròn lấy chiều rộng làm đường kính nên cột chiều cao để trống là ĐÚNG; với hai
        -- hình còn lại thì để trống là dữ liệu thiếu.
        AND (stage_shape = 'CIRCLE' OR (stage_height IS NOT NULL AND stage_height > 0)))
);

ALTER TABLE concert_templates DROP CONSTRAINT ck_template_stage;
ALTER TABLE concert_templates ADD CONSTRAINT ck_template_stage CHECK (
    stage_shape IS NULL
    OR (stage_shape IN ('RECTANGLE', 'CIRCLE', 'THRUST')
        AND stage_x IS NOT NULL
        AND stage_y IS NOT NULL
        AND stage_width IS NOT NULL AND stage_width > 0
        AND (stage_shape = 'CIRCLE' OR (stage_height IS NOT NULL AND stage_height > 0)))
);
