-- Ảnh sơ đồ khu vực ghế, do ban tổ chức tải lên.
--
-- Hệ thống đã tự vẽ được sơ đồ khán phòng từ hình học của địa điểm (V0102), nhưng bản vẽ ấy chỉ
-- nói được thứ nó biết: khu nằm đâu, bao nhiêu hàng. Nó không nói được "khu A nhìn thẳng sân
-- khấu", không vẽ được lối vào, và không mang bảng giá in kèm — những thứ ban tổ chức vốn đã có
-- sẵn trong tấm sơ đồ họ dùng để bán vé ngoài đời.
--
-- HAI CHỖ, cố ý:
--
--   * venues.seat_map_image_url — sơ đồ của địa điểm, dùng lại cho mọi sự kiện diễn ra ở đó. Đây
--     là chỗ đúng cho một nhà hát: hình dạng khán phòng không đổi theo từng đêm diễn.
--   * events.seat_map_image_url — sơ đồ riêng của một sự kiện, ĐÈ LÊN sơ đồ địa điểm khi có. Cần
--     nó vì giá vé và tên hạng vé thay đổi theo sự kiện, mà rất nhiều sơ đồ bán vé in giá ngay
--     trên hình.
--
-- Không có ràng buộc "phải có một trong hai": không có ảnh nào là trạng thái bình thường, và khi
-- ấy khách vẫn thấy sơ đồ hệ thống tự vẽ.
ALTER TABLE venues ADD COLUMN seat_map_image_url TEXT;
ALTER TABLE events ADD COLUMN seat_map_image_url TEXT;
