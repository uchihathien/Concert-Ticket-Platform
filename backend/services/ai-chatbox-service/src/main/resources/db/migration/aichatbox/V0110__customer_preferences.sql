-- Sở thích mua vé khách TỰ NÓI với trợ lý (thể loại, thành phố, ngân sách, một ghi chú ngắn).
--
-- Chỉ lưu những gì khách nói ra trong chat — không suy từ hành vi, không lấy từ nguồn khác. Lịch sử mua
-- KHÔNG chép vào đây: nó đã nằm ở ordering, và tool hồ sơ đọc thẳng từ đó mỗi lượt.
--
-- Một dòng cho một khách. Khách bảo "quên đi" (tool forgetCustomerPreferences) hoặc gọi
-- DELETE /v1/chat/agent/preferences thì dòng bị XOÁ hẳn, không đánh dấu mềm: dữ liệu cá nhân khách đã
-- yêu cầu xoá không được nằm lại.
CREATE TABLE customer_preferences (
    user_id        UUID PRIMARY KEY,
    -- Mã nhóm của catalog: nhac-song, san-khau, the-thao, hoi-thao. Tối đa 4.
    categories     TEXT[]      NOT NULL DEFAULT '{}',
    -- Thành phố, mới nhất đứng đầu. Tối đa 3.
    cities         TEXT[]      NOT NULL DEFAULT '{}',
    max_budget_vnd BIGINT,
    -- Một câu tự do ("thích ngồi gần sân khấu"). Không chứa số điện thoại hay email — use case lọc trước.
    note           TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_pref_budget CHECK (max_budget_vnd IS NULL OR max_budget_vnd > 0),
    CONSTRAINT ck_pref_note   CHECK (note IS NULL OR char_length(note) <= 200),
    CONSTRAINT ck_pref_sizes  CHECK (cardinality(categories) <= 4 AND cardinality(cities) <= 3)
);
