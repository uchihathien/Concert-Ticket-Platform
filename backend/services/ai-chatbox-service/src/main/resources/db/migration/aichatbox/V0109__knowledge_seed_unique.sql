-- Một tiêu đề cho mỗi đoạn tri thức CHUNG (event_id is null) — story 6.5, R-11.
--
-- Seeder tri thức nền nạp theo tiêu đề còn thiếu; hai replica khởi động cùng lúc trên DB trống
-- từng nạp đôi cả kho vì bảng không có ràng buộc nào. Index này là chốt cuối: dù khoá advisory
-- của seeder có lọt, bản trùng vẫn không vào được.
--
-- DB đang chạy có thể đã mang bản trùng, nên dọn TRƯỚC rồi mới tạo index:

-- (a) Bản trùng HOÀN TOÀN (cùng tiêu đề và nội dung) là do R-11 sinh ra — xoá, giữ bản sớm nhất
--     (hoà thì id nhỏ nhất).
delete from event_knowledge_embeddings e
using (select id,
              row_number() over (partition by title, content order by created_at, id) as rn
       from event_knowledge_embeddings
       where event_id is null) d
where e.id = d.id
  and d.rn > 1;

-- (b) Cùng tiêu đề mà KHÁC nội dung thì có thể là công của đội vận hành — không xoá, chỉ đổi tên
--     bản sau thành "Tiêu đề (2)", "(3)"… theo thứ tự tạo để người soạn tự gộp lại.
update event_knowledge_embeddings e
set title = e.title || ' (' || r.n || ')'
from (select id,
             row_number() over (partition by title order by created_at, id) as n
      from event_knowledge_embeddings
      where event_id is null) r
where e.id = r.id
  and r.n > 1;

-- (c) Chỉ ràng buộc tri thức chung; đoạn của sự kiện được trùng tiêu đề với nhau và với đoạn chung.
create unique index uq_knowledge_global_title
    on event_knowledge_embeddings (title)
    where event_id is null;
