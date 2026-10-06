-- Hình bố cục thứ ba: TABLE — bàn tròn.
--
-- `rowCount` bàn, mỗi bàn `seatsPerRow` ghế quây quanh. Đây là cách ngồi của gala, tiệc cuối năm
-- và đêm nhạc phòng trà: khán giả ngồi đối diện nhau quanh bàn chứ không cùng nhìn về một hướng.
--
-- KHÔNG thêm cột nào. `layout_inner_radius` mang nghĩa "bán kính bàn" cho hình này, đúng như nó
-- mang nghĩa "bán kính hàng đầu" cho ARC — hai hình không bao giờ cùng tồn tại trên một khu, nên
-- một cột phục vụ được cả hai và schema không phình ra theo số hình. `layout_rotation_deg` xoay cả
-- khối bàn, giống như nó xoay cả khối ghế của GRID.
--
-- Số bàn mỗi hàng KHÔNG có cột: nó suy ra được (`ceil(sqrt(số bàn))`), và một cột suy ra được là
-- một cột sẽ có ngày mâu thuẫn với phần còn lại.
--
-- Ràng buộc viết theo đúng bài học của V0103: mọi cột được canh bằng phép so sánh phải có
-- `IS NOT NULL` tường minh đi kèm. So sánh với NULL cho ra NULL, và CHECK ĐI QUA khi biểu thức
-- bằng NULL — một khu TABLE thiếu bán kính bàn sẽ lưu được, rồi làm sơ đồ mặt bằng 500 vĩnh viễn.

ALTER TABLE venue_zones DROP CONSTRAINT ck_zone_layout;
ALTER TABLE venue_zones ADD CONSTRAINT ck_zone_layout CHECK (
    layout_shape IS NULL
    OR (layout_shape = 'GRID'
        AND layout_origin_x IS NOT NULL
        AND layout_origin_y IS NOT NULL)
    OR (layout_shape = 'TABLE'
        AND layout_origin_x IS NOT NULL
        AND layout_origin_y IS NOT NULL
        AND layout_inner_radius IS NOT NULL AND layout_inner_radius > 0)
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
    OR (layout_shape = 'TABLE'
        AND layout_origin_x IS NOT NULL
        AND layout_origin_y IS NOT NULL
        AND layout_inner_radius IS NOT NULL AND layout_inner_radius > 0)
    OR (layout_shape = 'ARC'
        AND layout_origin_x IS NOT NULL
        AND layout_origin_y IS NOT NULL
        AND layout_inner_radius IS NOT NULL AND layout_inner_radius > 0
        AND layout_start_angle_deg IS NOT NULL
        AND layout_end_angle_deg IS NOT NULL
        AND layout_end_angle_deg > layout_start_angle_deg
        AND layout_end_angle_deg - layout_start_angle_deg <= 360)
);
