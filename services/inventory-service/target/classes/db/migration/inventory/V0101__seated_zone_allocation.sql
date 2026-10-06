-- Mua vé ngồi theo KHU, không chỉ đích danh từng ghế.
--
-- Trước đây chỉ vé đứng mới cấp phát theo số lượng (idx_seat_standing_alloc). Vé ngồi buộc khách
-- phải bấm đúng ô ghế trên sơ đồ — mà sơ đồ ấy cần toạ độ khớp mặt bằng, một điều kiện không phải
-- suất nào cũng có. Nay khách nói "khu A, 2 vé" và hệ thống tự chọn chỗ còn trống gần sân khấu
-- nhất, nên đường mua không còn phụ thuộc vào việc vẽ được sơ đồ hay không.
--
-- Index này là bản sinh đôi của idx_seat_standing_alloc cho phía vé ngồi, khác đúng một chỗ: thứ
-- tự. Vé đứng ORDER BY id vì không đơn vị nào hơn đơn vị nào; vé ngồi thì có — hàng gần sân khấu
-- đáng giá hơn hàng cuối, nên cấp phát đi theo pos_y rồi pos_x. Thiếu hai cột ấy trong index thì
-- Postgres phải sort toàn bộ ghế trống của khu trước khi lấy 2 dòng đầu, và làm vậy dưới
-- FOR UPDATE SKIP LOCKED giữa đợt mở bán là tự tạo điểm nghẽn.
--
-- seat_code nằm cuối để câu lệnh không phải chạm bảng: nó là khoá phá hoà cho những suất chưa có
-- toạ độ (pos_x/pos_y NULL), thứ vẫn còn trong dữ liệu cũ.
CREATE INDEX idx_seat_seated_alloc ON session_seats (event_session_id, zone_code, pos_y, pos_x, seat_code)
    WHERE admission_type = 'SEATED' AND status = 'AVAILABLE';
