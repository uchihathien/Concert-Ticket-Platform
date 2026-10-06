-- =====================================================================
-- Sổ cái kép (ADR-1005).
--
-- Ba bất biến, và cả ba đều được ép ở TẦNG DATABASE chứ không chỉ ở application code:
--   1. Mọi bút toán có tổng Nợ = tổng Có          -> constraint trigger
--   2. Định khoản là bất biến, chỉ ghi thêm       -> REVOKE UPDATE/DELETE
--   3. Số tiền luôn dương                          -> CHECK
--
-- Lý do ép ở database: đây là hệ thống giữ tiền thật. Một bug trong application
-- không được phép làm sai sổ sách.
--
-- Quy ước tiền: BIGINT, đơn vị đồng VND, tên cột kết thúc _vnd. Không có "cents".
-- =====================================================================

CREATE TABLE ledger_accounts (
    id             UUID PRIMARY KEY,
    code           TEXT        NOT NULL,
    name           TEXT        NOT NULL,
    account_type   TEXT        NOT NULL,
    normal_balance TEXT        NOT NULL,
    owner_type     TEXT        NOT NULL,
    owner_id       UUID,
    currency       CHAR(3)     NOT NULL DEFAULT 'VND',
    status         TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_account_type CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    CONSTRAINT ck_normal_balance CHECK (normal_balance IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_owner_type CHECK (owner_type IN ('PLATFORM', 'ORGANIZATION', 'ORDER')),
    -- Tài khoản của nền tảng không có chủ; tài khoản theo tổ chức/đơn hàng thì bắt buộc có.
    CONSTRAINT ck_owner_id CHECK (
        (owner_type = 'PLATFORM' AND owner_id IS NULL)
        OR (owner_type <> 'PLATFORM' AND owner_id IS NOT NULL)
    )
);

-- Mỗi (mã tài khoản, chủ sở hữu) chỉ tồn tại một lần.
-- Dùng hai partial index vì UNIQUE thường coi mọi NULL là khác nhau.
CREATE UNIQUE INDEX uq_account_platform ON ledger_accounts (code) WHERE owner_type = 'PLATFORM';
CREATE UNIQUE INDEX uq_account_owned ON ledger_accounts (code, owner_type, owner_id) WHERE owner_type <> 'PLATFORM';
CREATE INDEX idx_account_owner ON ledger_accounts (owner_type, owner_id);

CREATE TABLE journal_entries (
    id                UUID PRIMARY KEY,
    seq               BIGSERIAL   NOT NULL UNIQUE,   -- thứ tự ghi sổ toàn cục
    entry_type        TEXT        NOT NULL,
    occurred_at       TIMESTAMPTZ NOT NULL,          -- thời điểm nghiệp vụ xảy ra
    recorded_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    source_type       TEXT        NOT NULL,
    source_id         UUID        NOT NULL,
    organization_id   UUID,
    correlation_id    TEXT,
    -- Consumer retry là chuyện thường ngày ở hệ phân tán; khoá này chặn ghi sổ hai lần.
    idempotency_key   TEXT        NOT NULL UNIQUE,
    memo              TEXT,
    reverses_entry_id UUID REFERENCES journal_entries (id),
    created_by        TEXT        NOT NULL,

    CONSTRAINT ck_source_type CHECK (source_type IN ('ORDER', 'PAYMENT', 'PAYOUT', 'REFUND', 'ADJUSTMENT'))
);

CREATE INDEX idx_entry_source ON journal_entries (source_type, source_id);
CREATE INDEX idx_entry_org_time ON journal_entries (organization_id, occurred_at DESC);

CREATE TABLE postings (
    id         UUID PRIMARY KEY,
    entry_id   UUID   NOT NULL REFERENCES journal_entries (id),
    account_id UUID   NOT NULL REFERENCES ledger_accounts (id),
    direction  TEXT   NOT NULL,
    amount_vnd BIGINT NOT NULL,
    line_no    INT    NOT NULL,

    CONSTRAINT ck_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    -- Sổ cái không có tiền âm. Chiều tiền biểu diễn bằng direction, không bằng dấu.
    CONSTRAINT ck_amount_positive CHECK (amount_vnd > 0),
    CONSTRAINT uq_posting_line UNIQUE (entry_id, line_no)
);

CREATE INDEX idx_posting_account ON postings (account_id, id);
CREATE INDEX idx_posting_entry ON postings (entry_id);

-- ---------------------------------------------------------------------
-- Bất biến 1: mọi bút toán phải cân.
--
-- CONSTRAINT TRIGGER + DEFERRABLE INITIALLY DEFERRED cho phép chèn từng dòng
-- rồi mới kiểm tra lúc COMMIT — nếu kiểm ngay từng dòng thì dòng đầu tiên của
-- mọi bút toán đều lệch và không bao giờ ghi được gì.
--
-- Bút toán lệch KHÔNG THỂ vào được database, kể cả khi application code sai.
-- ---------------------------------------------------------------------
CREATE FUNCTION assert_entry_balanced() RETURNS TRIGGER
LANGUAGE plpgsql AS $fn$
DECLARE
    total_debit  BIGINT;
    total_credit BIGINT;
BEGIN
    SELECT COALESCE(SUM(amount_vnd) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount_vnd) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO total_debit, total_credit
      FROM postings
     WHERE entry_id = NEW.entry_id;

    IF total_debit <> total_credit THEN
        RAISE EXCEPTION 'But toan % khong can: No=% Co=%', NEW.entry_id, total_debit, total_credit
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$fn$;

CREATE CONSTRAINT TRIGGER trg_entry_balanced
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_entry_balanced();

-- ---------------------------------------------------------------------
-- Bất biến 2: sổ cái chỉ ghi thêm.
--
-- Sửa sai bằng bút toán đảo có reverses_entry_id, không bao giờ sửa bản ghi cũ.
-- Ép bằng quyền database chứ không bằng quy ước, để bug trong application cũng
-- không xoá được lịch sử.
--
-- current_user ở môi trường local là owner của database nên REVOKE không có tác
-- dụng với chính nó; ở staging/production, service chạy bằng một role riêng
-- không phải owner và lệnh này mới thực sự chặn.
-- ---------------------------------------------------------------------
REVOKE UPDATE, DELETE, TRUNCATE ON postings FROM PUBLIC;
REVOKE UPDATE, DELETE, TRUNCATE ON journal_entries FROM PUBLIC;

-- ---------------------------------------------------------------------
-- Ảnh chụp số dư.
--
-- Số dư = snapshot gần nhất + các định khoản phát sinh sau đó. Cách này tránh
-- hoàn toàn việc UPDATE một hàng số dư nóng — thứ sẽ thành điểm nghẽn khi 10k
-- người mua vé cùng một tổ chức, và tệ hơn là thành nguồn sai số nếu có bug.
-- ---------------------------------------------------------------------
CREATE TABLE account_balance_snapshots (
    account_id  UUID        NOT NULL REFERENCES ledger_accounts (id),
    as_of_seq   BIGINT      NOT NULL,
    balance_vnd BIGINT      NOT NULL,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, as_of_seq)
);

-- ---------------------------------------------------------------------
-- Hệ thống tài khoản của nền tảng (custodial-funds.md §2).
-- Tài khoản theo từng tổ chức (2011/2012/2013) được tạo khi nhận OrganizationCreated.
-- ---------------------------------------------------------------------
INSERT INTO ledger_accounts (id, code, name, account_type, normal_balance, owner_type) VALUES
    (gen_random_uuid(), '1010', 'Tien - TK ky quy',            'ASSET',     'DEBIT',  'PLATFORM'),
    (gen_random_uuid(), '1020', 'Tien - TK van hanh',          'ASSET',     'DEBIT',  'PLATFORM'),
    (gen_random_uuid(), '2030', 'Tai khoan treo',              'LIABILITY', 'CREDIT', 'PLATFORM'),
    (gen_random_uuid(), '4010', 'Doanh thu hoa hong',          'REVENUE',   'CREDIT', 'PLATFORM'),
    (gen_random_uuid(), '5010', 'Chi phi cong thanh toan',     'EXPENSE',   'DEBIT',  'PLATFORM'),
    (gen_random_uuid(), '5020', 'Phi chuyen khoan chi tra',    'EXPENSE',   'DEBIT',  'PLATFORM');
