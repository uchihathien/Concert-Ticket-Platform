-- payout-service: đích chi trả, lô chi trả, phê duyệt.
--
-- Quy ước tiền: cột VND là BIGINT, tên kết thúc _vnd. Không có _cents ở bất cứ đâu.
--
-- CÔNG CỤ NỘI BỘ CỦA SUPERADMIN. Không có endpoint nào cho tổ chức, và không bảng nào ở
-- đây được tổ chức đọc (ADR-1010). Tổ chức chỉ thấy số vé và số tiền đã bán; việc tiền đi
-- đâu, khi nào, do ai duyệt là chuyện của nền tảng.

-- ---------------------------------------------------------------------------
-- Đích chi trả của một tổ chức.
--
-- Đây là chỗ ở mới của bảng bank_accounts trong v1, nhưng đổi cả ý nghĩa lẫn chủ sở hữu:
-- v1 là "tài khoản nhận tiền của tổ chức, tổ chức tự nhập"; giờ là "đích chi trả, do
-- SUPERADMIN nhập và quản lý". Tổ chức tự nhập số tài khoản là một đường tấn công hiển
-- nhiên — đổi số tài khoản ngay trước đợt chi trả là lấy được tiền của người khác.
-- ---------------------------------------------------------------------------
CREATE TABLE payout_accounts (
    id                UUID PRIMARY KEY,
    organization_id   UUID        NOT NULL,
    bank_bin          TEXT        NOT NULL,
    bank_name         TEXT        NOT NULL,
    account_number    TEXT        NOT NULL,

    -- Tên chủ tài khoản, dùng để đối chiếu với hồ sơ pháp nhân trước khi chi trả.
    account_holder    TEXT        NOT NULL,

    is_active         BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        UUID        NOT NULL,

    CONSTRAINT ck_payout_bank_bin CHECK (bank_bin ~ '^[0-9]{6}$'),
    CONSTRAINT ck_payout_account  CHECK (account_number ~ '^[0-9]{4,20}$'),
    CONSTRAINT uq_payout_account  UNIQUE (organization_id, bank_bin, account_number)
);

-- Mỗi tổ chức nhiều nhất một đích chi trả đang hoạt động: hai đích active nghĩa là lúc chi
-- trả phải đoán chuyển vào đâu.
CREATE UNIQUE INDEX uq_single_active_payout_account ON payout_accounts (organization_id)
    WHERE is_active;

-- ---------------------------------------------------------------------------
-- Lô chi trả.
--
-- MVP là chi trả THỦ CÔNG CÓ PHÊ DUYỆT: hệ thống tạo lô, người vận hành chuyển khoản qua
-- ngân hàng, rồi xác nhận lại. Tự động hoá bằng API ngân hàng chỉ làm sau khi quy trình thủ
-- công đã chạy ổn định vài tháng — một lỗi trong tự động hoá chi trả là tiền đi mất thật.
-- ---------------------------------------------------------------------------
CREATE TABLE payout_batches (
    id                UUID PRIMARY KEY,
    organization_id   UUID        NOT NULL,
    payout_account_id UUID        NOT NULL REFERENCES payout_accounts (id),

    amount_vnd        BIGINT      NOT NULL,

    -- Snapshot đích chuyển tiền tại thời điểm tạo lô. Superadmin đổi đích sau đó không được
    -- làm đổi nơi tiền của lô đã duyệt đi tới.
    bank_bin          TEXT        NOT NULL,
    account_number    TEXT        NOT NULL,
    account_holder    TEXT        NOT NULL,

    status            TEXT        NOT NULL DEFAULT 'PENDING_APPROVAL',

    created_by        UUID        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    approved_by       UUID,
    approved_at       TIMESTAMPTZ,

    -- Mã giao dịch ngân hàng do người vận hành nhập khi xác nhận đã chuyển.
    bank_reference    TEXT,
    completed_at      TIMESTAMPTZ,

    rejected_reason   TEXT,

    CONSTRAINT ck_batch_status CHECK (
        status IN ('PENDING_APPROVAL', 'APPROVED', 'COMPLETED', 'REJECTED')),
    CONSTRAINT ck_batch_amount CHECK (amount_vnd > 0),

    -- NGUYÊN TẮC BỐN MẮT, ép ở tầng database chứ không chỉ ở code.
    --
    -- Người tạo lô không được là người duyệt. Kiểm ở Java là đủ cho đường thường, nhưng
    -- ràng buộc này còn chặn cả một script sửa dữ liệu chạy vội lúc 2 giờ sáng — và chi trả
    -- là chỗ mà "đường thường" không phải là mối lo duy nhất.
    CONSTRAINT ck_four_eyes CHECK (approved_by IS NULL OR approved_by <> created_by),
    CONSTRAINT ck_approved_pair  CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
    CONSTRAINT ck_completed_pair CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE INDEX idx_batch_org     ON payout_batches (organization_id, created_at DESC);
CREATE INDEX idx_batch_pending ON payout_batches (created_at) WHERE status = 'PENDING_APPROVAL';

-- ---------------------------------------------------------------------------
-- Đối soát ngân hàng theo ngày.
--
-- Cổng chặn số 3 trước khi chi trả: chưa đóng đối soát hôm trước thì không chi trả. Lý do
-- không phải kỹ thuật mà là kế toán — chi tiền khi chưa biết chắc hôm qua thu vào bao nhiêu
-- là chi trên một con số có thể sai.
-- ---------------------------------------------------------------------------
CREATE TABLE reconciliation_days (
    business_date   DATE PRIMARY KEY,
    status          TEXT        NOT NULL DEFAULT 'OPEN',
    expected_vnd    BIGINT,
    actual_vnd      BIGINT,
    variance_vnd    BIGINT,
    closed_at       TIMESTAMPTZ,
    closed_by       UUID,
    note            TEXT,

    CONSTRAINT ck_recon_status CHECK (status IN ('OPEN', 'CLOSED', 'DISCREPANCY'))
);
