-- Ca trực soát vé: cổng của sự kiện, ca theo suất + cổng, và phiên làm việc thật của từng nhân viên.
--
-- ---------------------------------------------------------------------------
-- Vì sao nằm ở ticketing, cạnh checkin_assignments
-- ---------------------------------------------------------------------------
-- Ca trực là thứ được kiểm ở MỖI lượt quét ("nhân viên này có đang trong ca của suất này không"),
-- và lượt quét chạy ở đây. Đặt ở service khác là thêm một lời gọi mạng vào đường nóng nhất của cả
-- hệ thống — giờ mở cửa.
--
-- ---------------------------------------------------------------------------
-- Ca (kế hoạch) khác phiên ca (thực tế)
-- ---------------------------------------------------------------------------
-- checkin_shifts là lịch ban tổ chức xếp: suất nào, cổng nào, mấy giờ, ai. checkin_shift_sessions
-- là lúc nhân viên THẬT SỰ bấm "Bắt đầu ca" và "Kết thúc ca". Tổng kết bàn giao tính theo phiên,
-- không theo lịch — người tới muộn 20 phút không được tính 20 phút đó.

CREATE TABLE checkin_gates (
    id              UUID PRIMARY KEY,
    organization_id UUID        NOT NULL,
    event_id        UUID        NOT NULL,
    name            TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_checkin_gate_name UNIQUE (event_id, name),
    CONSTRAINT ck_checkin_gate_name CHECK (length(btrim(name)) BETWEEN 1 AND 40)
);

CREATE INDEX idx_checkin_gates_org ON checkin_gates (organization_id, event_id);

CREATE TABLE checkin_shifts (
    id               UUID PRIMARY KEY,
    organization_id  UUID        NOT NULL,
    event_id         UUID        NOT NULL,
    event_session_id UUID        NOT NULL,
    -- RESTRICT: xoá một cổng đang có ca là xoá mất nơi nhân viên được giao đứng.
    gate_id          UUID        NOT NULL REFERENCES checkin_gates (id) ON DELETE RESTRICT,
    starts_at        TIMESTAMPTZ NOT NULL,
    ends_at          TIMESTAMPTZ NOT NULL,
    created_by       UUID,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_checkin_shift_window CHECK (ends_at > starts_at)
);

CREATE INDEX idx_checkin_shifts_session ON checkin_shifts (event_session_id, starts_at);
CREATE INDEX idx_checkin_shifts_event ON checkin_shifts (organization_id, event_id, starts_at);

CREATE TABLE checkin_shift_staff (
    shift_id UUID NOT NULL REFERENCES checkin_shifts (id) ON DELETE CASCADE,
    -- users.id của identity-service, cùng quy ước với checkin_assignments.
    user_id  UUID NOT NULL,
    PRIMARY KEY (shift_id, user_id)
);

CREATE INDEX idx_checkin_shift_staff_user ON checkin_shift_staff (user_id);

CREATE TABLE checkin_shift_sessions (
    id         UUID PRIMARY KEY,
    -- CASCADE: xoá ca thì xoá luôn phiên; lượt quét trong checkin_log vẫn còn nguyên.
    shift_id   UUID        NOT NULL REFERENCES checkin_shifts (id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL,
    device_id  TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    -- NULL khi đang trực.
    ended_at   TIMESTAMPTZ,
    CONSTRAINT ck_shift_session_window CHECK (ended_at IS NULL OR ended_at >= started_at)
);

-- Mỗi người chỉ có MỘT phiên đang mở trong một ca: bấm "Bắt đầu" hai lần là quay lại phiên cũ.
CREATE UNIQUE INDEX uq_shift_session_open ON checkin_shift_sessions (shift_id, user_id) WHERE ended_at IS NULL;
-- Lượt quét hỏi "người này có phiên đang mở không" — đường nóng.
CREATE INDEX idx_shift_sessions_open_user ON checkin_shift_sessions (user_id) WHERE ended_at IS NULL;
