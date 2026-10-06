-- Cùng lỗ NULL của V0103, ở hai ràng buộc còn lại — và hai cái này có từ V0100.
--
-- `ck_zone_shape` trông như đang canh đủ, vì `capacity IS NULL` và `row_count IS NULL` đều có mặt
-- trong biểu thức. Nhưng chúng nằm ở NHÁNH KIA:
--
--     (kind = 'SEATED'   AND row_count > 0 AND seats_per_row > 0 AND capacity IS NULL)
--  OR (kind = 'STANDING' AND capacity > 0  AND row_count IS NULL AND seats_per_row IS NULL)
--
-- Một khu SEATED thiếu `row_count`: nhánh đầu cho ra NULL (`NULL > 0` là NULL), nhánh sau cho ra
-- sai (kind không phải STANDING). `NULL OR false` = NULL, và CHECK **đi qua** khi biểu thức bằng
-- NULL. Cả ba trạng thái hỏng đều lưu được: SEATED thiếu số hàng, SEATED thiếu số ghế mỗi hàng,
-- STANDING thiếu sức chứa.
--
-- Hậu quả: `coalesce(z.capacity, z.row_count * z.seats_per_row)` cho ra NULL, `rs.getInt` biến nó
-- thành 0 mà không báo gì, và hạng vé hiện ra trên trang công khai với sức chứa 0. Khu SEATED ấy
-- cũng không sinh được ghế nào — nó tồn tại trong sơ đồ nhưng không bán được gì.
--
-- KHÁC V0103 ở phần dọn dữ liệu: ở đó bố cục thiếu có thể đặt về NULL vì NULL nghĩa là "xếp tự
-- động" và khu vẫn hiện ra. Ở đây không có giá trị dự phòng nào an toàn — bịa ra số ghế là bịa ra
-- thứ đang được bán. Nên không có UPDATE nào: nếu tồn tại hàng hỏng thì `ADD CONSTRAINT` tự hỏng,
-- và người triển khai phải nhìn vào từng khu để quyết định. Hỏng ồn ào đúng hơn là sửa im lặng.

ALTER TABLE venue_zones DROP CONSTRAINT ck_zone_shape;
ALTER TABLE venue_zones ADD CONSTRAINT ck_zone_shape CHECK (
    (kind = 'SEATED'
        AND row_count IS NOT NULL AND row_count > 0
        AND seats_per_row IS NOT NULL AND seats_per_row > 0
        AND capacity IS NULL)
    OR (kind = 'STANDING'
        AND capacity IS NOT NULL AND capacity > 0
        AND row_count IS NULL
        AND seats_per_row IS NULL)
);

ALTER TABLE concert_template_zones DROP CONSTRAINT ck_template_zone_shape;
ALTER TABLE concert_template_zones ADD CONSTRAINT ck_template_zone_shape CHECK (
    (kind = 'SEATED'
        AND row_count IS NOT NULL AND row_count > 0
        AND seats_per_row IS NOT NULL AND seats_per_row > 0
        AND capacity IS NULL)
    OR (kind = 'STANDING'
        AND capacity IS NOT NULL AND capacity > 0
        AND row_count IS NULL
        AND seats_per_row IS NULL)
);
