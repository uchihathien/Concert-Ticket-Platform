-- Migration đầu tiên của ai-chatbox-service.
-- Quy ước tiền: cột VND là BIGINT, tên kết thúc _vnd. Không có _cents ở bất cứ đâu.

-- Extension `vector` KHÔNG tạo ở đây.
--
-- pgvector không phải extension "trusted", nên CREATE EXTENSION đòi quyền superuser — mà Flyway
-- chạy bằng role `ai_chatbox`, chủ database chứ không phải superuser. Đặt lệnh đó vào migration
-- thì mọi môi trường sạch đều chết ở lần khởi động đầu với "permission denied to create
-- extension", và thông báo ấy trỏ vào Flyway trong khi nguyên nhân là phân quyền của cụm.
--
-- Nó được tạo bởi `postgres` trong deploy/compose/initdb/01-databases.sql. Migration này chỉ DÙNG
-- kiểu `vector`, và nếu extension chưa có thì lệnh dưới đây hỏng ngay lập tức với "type vector
-- does not exist" — một thông báo nói đúng vấn đề.

create table chat_sessions (
    id         uuid primary key,
    user_id    uuid        not null,
    created_at timestamptz not null default now()
);

-- Tra "các phiên của một người" là truy vấn duy nhất ngoài tra theo id.
create index idx_chat_sessions_user on chat_sessions (user_id, created_at desc);

create table chat_messages (
    id         bigserial primary key,
    session_id uuid        not null references chat_sessions (id) on delete cascade,
    role       text        not null check (role in ('USER', 'ASSISTANT')),
    content    text        not null,
    created_at timestamptz not null default now()
);

-- Index khớp đúng câu truy vấn duy nhất chạy trên bảng này: N lượt mới nhất của một phiên.
-- Thứ tự cột (session_id, created_at desc, id desc) phải khớp mệnh đề ORDER BY, nếu không
-- Postgres vẫn dùng index rồi sắp xếp lại — và cái giá đó trả ở mỗi lượt chat.
create index idx_chat_messages_session on chat_messages (session_id, created_at desc, id desc);

-- Kho tri thức cho RAG.
--
-- `vector(1024)` khớp voyage-3.5. Số này là hợp đồng với cấu hình
-- `nexaticket.aichatbox.embedding.dimensions`: đổi mô hình nhúng mà không đổi cả hai thì hoặc
-- Postgres từ chối ghi (trường hợp may), hoặc mô hình mới cũng 1024 chiều và mọi thứ chạy trơn tru
-- với kết quả vô nghĩa — vector của hai mô hình không nằm trong cùng một không gian.
create table event_knowledge_embeddings (
    id         uuid primary key     default gen_random_uuid(),
    -- NULL nghĩa là tri thức chung của nền tảng (chính sách hoàn vé, cách đặt chỗ), không thuộc
    -- sự kiện nào.
    event_id   uuid,
    title      text        not null,
    content    text        not null,
    embedding  vector(1024) not null,
    created_at timestamptz not null default now()
);

-- HNSW chứ không phải IVFFlat: IVFFlat đòi dữ liệu đã có sẵn lúc tạo index để chia cụm, nên xây
-- nó trên bảng rỗng cho ra một index vô dụng và không có cảnh báo nào. HNSW xây tăng dần, đúng với
-- một kho tri thức được nạp dần.
--
-- `vector_cosine_ops` phải khớp toán tử truy vấn `<=>`. Khai `vector_l2_ops` rồi truy vấn bằng
-- `<=>` thì Postgres bỏ qua index và quét toàn bảng — vẫn đúng kết quả, chỉ chậm dần theo thời
-- gian, và không có gì trong log nói ra.
create index idx_knowledge_embedding on event_knowledge_embeddings
    using hnsw (embedding vector_cosine_ops);

-- Quy định của sự kiện — tra CHÍNH XÁC theo id, không phải tìm ngữ nghĩa.
--
-- Tách khỏi bảng trên vì hai cách dùng khác nhau: tool getEventRules cần bản đầy đủ và đúng của
-- một sự kiện cụ thể, còn RAG cần những mảnh gần nghĩa. Nhét chung thì câu hỏi "sự kiện này cấm
-- mang gì" trả về một mảnh quy định của sự kiện khác chỉ vì nó gần nghĩa hơn.
create table event_rules (
    event_id    uuid primary key,
    event_title text        not null,
    content     text        not null,
    -- Bản nháp không được lộ cho khách. Mặc định false: quên bật thì khách thấy "chưa có quy định"
    -- — sai theo hướng an toàn; mặc định true thì bản nháp đi thẳng ra ngoài.
    published   boolean     not null default false,
    updated_at  timestamptz not null default now()
);
