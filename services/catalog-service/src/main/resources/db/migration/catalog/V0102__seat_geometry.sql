-- Hình học mặt bằng: sân khấu nằm đâu, mỗi khu nằm đâu.
--
-- Trước migration này một khu chỉ có số hàng × số ghế, nên mọi sơ đồ đều là những khối chữ nhật
-- xếp dọc. Đủ cho nhà hát, sai cho mọi khán phòng vây quanh sân khấu — mà đó là hình dạng của
-- gần như mọi concert.
--
-- ---------------------------------------------------------------------------
-- Vì sao toạ độ ghế KHÔNG nằm ở đây
-- ---------------------------------------------------------------------------
-- Bảng này lưu CÔNG THỨC, không lưu kết quả: một khu cung 40 hàng × 60 ghế là 7 con số ở đây và
-- 2.400 dòng nếu lưu từng ghế. Toạ độ từng ghế được tính lúc publish, đi theo `session.published`,
-- và nằm ở `seats.pos_x/pos_y` của inventory_db — nơi duy nhất thật sự đọc tới từng ghế.
--
-- Hệ quả đáng biết: sửa hình học sau khi publish KHÔNG di chuyển ghế đã dựng. Đó là hành vi đúng —
-- vé đã bán mang mã chỗ, và mã chỗ phải trỏ tới đúng chỗ ngồi hôm mở bán. Muốn đổi sơ đồ thì rút
-- sự kiện xuống rồi publish lại, cùng đường đã có.
--
-- ---------------------------------------------------------------------------
-- Vì sao mọi cột đều cho phép NULL
-- ---------------------------------------------------------------------------
-- NULL nghĩa là "chưa đặt", và bố cục tự động xếp khu ấy xuống dưới sân khấu (FloorPlan). Nó khác
-- hẳn "đã đặt, trùng chỗ mặc định": khu chưa đặt sẽ tự dịch xuống khi ban tổ chức chèn thêm một
-- khu phía trên, còn khu đã đặt thì đứng yên. Ép một giá trị mặc định lúc ghi là xoá mất phân
-- biệt đó, và tổ chức nào cũng sẽ có một khu đứng yên mà không ai hiểu vì sao.
--
-- Đó cũng là lý do migration này không có bước backfill: mọi khu đang có giữ nguyên NULL và tiếp
-- tục được xếp như trước, không một sơ đồ nào đang chạy bị xê dịch.

-- ---------------------------------------------------------------------------
-- Sân khấu
-- ---------------------------------------------------------------------------
-- Đơn vị của mọi cột hình học trong migration này là "ghế" — một đơn vị là khoảng cách giữa hai
-- ghế cạnh nhau. Không phải pixel, không phải mét. Cùng một sơ đồ được vẽ trên điện thoại 360px,
-- màn hình quản trị 1600px và ảnh poster 2480px; con số duy nhất đúng ở cả ba chỗ là con số không
-- mang đơn vị hiển thị nào.
--
-- Quy ước hướng: sân khấu ở phía trên (y âm), khán giả phía dưới (y dương). Nhờ nó "hàng 1" luôn
-- là hàng gần sân khấu ở mọi khán phòng, kể cả khán phòng tròn.

ALTER TABLE venues
    ADD COLUMN stage_shape  TEXT,
    ADD COLUMN stage_x      NUMERIC(8, 2),
    ADD COLUMN stage_y      NUMERIC(8, 2),
    ADD COLUMN stage_width  NUMERIC(8, 2),
    ADD COLUMN stage_height NUMERIC(8, 2);

ALTER TABLE concert_templates
    ADD COLUMN stage_shape  TEXT,
    ADD COLUMN stage_x      NUMERIC(8, 2),
    ADD COLUMN stage_y      NUMERIC(8, 2),
    ADD COLUMN stage_width  NUMERIC(8, 2),
    ADD COLUMN stage_height NUMERIC(8, 2);

-- Khai sân khấu là khai TRỌN hoặc không khai gì. Nửa vời — có hình dạng mà không có bề ngang —
-- sẽ đọc lên thành một sân khấu rộng 0 đơn vị, tức là một sân khấu vô hình trên sơ đồ của khách.
--
-- CIRCLE dùng stage_width làm đường kính và bỏ qua stage_height, nên nó không đòi cột ấy.
ALTER TABLE venues
    ADD CONSTRAINT ck_venue_stage CHECK (
        stage_shape IS NULL
     OR (stage_shape IN ('RECTANGLE', 'CIRCLE', 'THRUST')
     AND stage_x IS NOT NULL AND stage_y IS NOT NULL
     AND stage_width > 0
     AND (stage_shape = 'CIRCLE' OR stage_height > 0)));

ALTER TABLE concert_templates
    ADD CONSTRAINT ck_template_stage CHECK (
        stage_shape IS NULL
     OR (stage_shape IN ('RECTANGLE', 'CIRCLE', 'THRUST')
     AND stage_x IS NOT NULL AND stage_y IS NOT NULL
     AND stage_width > 0
     AND (stage_shape = 'CIRCLE' OR stage_height > 0)));

-- ---------------------------------------------------------------------------
-- Bố cục của khu
-- ---------------------------------------------------------------------------
-- GRID: khối chữ nhật đặt tại (origin_x, origin_y), xoay rotation_deg quanh chính gốc đó.
-- ARC:  các hàng là cung tròn đồng tâm quanh (origin_x, origin_y), hàng đầu ở bán kính
--       inner_radius, ghế rải đều trong [start_angle_deg, end_angle_deg].
--
-- Hai hình này dựng được mọi khán phòng thật: sân khấu chữ nhật là GRID xếp thẳng, sân khấu tròn
-- là ARC quây quanh một tâm, sân khấu chữ U là một ARC ôm đầu sân khấu cộng hai GRID xoay 90° chạy
-- dọc hai cánh. Hình thứ ba (đa giác tự do) nghĩa là màn hình khai báo khu phải thành trình vẽ
-- vector, và mã chỗ A-3-12 mất nghĩa "hàng 3 ghế 12" mà soát vé lẫn hỗ trợ khách hàng đang đọc.

ALTER TABLE venue_zones
    ADD COLUMN layout_shape           TEXT,
    ADD COLUMN layout_origin_x        NUMERIC(8, 2),
    ADD COLUMN layout_origin_y        NUMERIC(8, 2),
    ADD COLUMN layout_rotation_deg    NUMERIC(8, 2),
    ADD COLUMN layout_inner_radius    NUMERIC(8, 2),
    ADD COLUMN layout_start_angle_deg NUMERIC(8, 2),
    ADD COLUMN layout_end_angle_deg   NUMERIC(8, 2);

ALTER TABLE concert_template_zones
    ADD COLUMN layout_shape           TEXT,
    ADD COLUMN layout_origin_x        NUMERIC(8, 2),
    ADD COLUMN layout_origin_y        NUMERIC(8, 2),
    ADD COLUMN layout_rotation_deg    NUMERIC(8, 2),
    ADD COLUMN layout_inner_radius    NUMERIC(8, 2),
    ADD COLUMN layout_start_angle_deg NUMERIC(8, 2),
    ADD COLUMN layout_end_angle_deg   NUMERIC(8, 2);

-- Ràng buộc chép nguyên sang cả hai bảng, không nới ở bảng khung — cùng lý do với ck_template_zone_shape
-- (V0101): nới ở khung nghĩa là khung lưu được thứ venue_zones từ chối, và lỗi chỉ lộ ra lúc tổ
-- chức áp khung, ở màn hình của người không gây ra lỗi và không sửa được nó.
--
-- Cung quét quá 360° bị chặn vì nó chồng lên chính mình: ghế 1 và ghế cuối nằm đúng một chỗ, và
-- hai vé khác nhau chỉ vào cùng một điểm trên sơ đồ.
-- Biểu thức viết thẳng hai lần thay vì gọi một hàm dùng chung. Hàm trong CHECK là hợp lệ với
-- Postgres nhưng pg_dump phục hồi bảng TRƯỚC hàm, nên một bản dump sạch sẽ khôi phục hỏng — và nó
-- hỏng ở môi trường mới, lúc không ai đang nhìn migration này.
ALTER TABLE venue_zones
    ADD CONSTRAINT ck_zone_layout CHECK (
        layout_shape IS NULL
     OR (layout_shape = 'GRID'
     AND layout_origin_x IS NOT NULL AND layout_origin_y IS NOT NULL)
     OR (layout_shape = 'ARC'
     AND layout_origin_x IS NOT NULL AND layout_origin_y IS NOT NULL
     AND layout_inner_radius > 0
     AND layout_start_angle_deg IS NOT NULL AND layout_end_angle_deg IS NOT NULL
     AND layout_end_angle_deg > layout_start_angle_deg
     AND layout_end_angle_deg - layout_start_angle_deg <= 360));

ALTER TABLE concert_template_zones
    ADD CONSTRAINT ck_template_zone_layout CHECK (
        layout_shape IS NULL
     OR (layout_shape = 'GRID'
     AND layout_origin_x IS NOT NULL AND layout_origin_y IS NOT NULL)
     OR (layout_shape = 'ARC'
     AND layout_origin_x IS NOT NULL AND layout_origin_y IS NOT NULL
     AND layout_inner_radius > 0
     AND layout_start_angle_deg IS NOT NULL AND layout_end_angle_deg IS NOT NULL
     AND layout_end_angle_deg > layout_start_angle_deg
     AND layout_end_angle_deg - layout_start_angle_deg <= 360));
