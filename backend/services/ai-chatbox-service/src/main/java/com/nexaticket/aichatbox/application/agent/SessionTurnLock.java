// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.agent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Một phiên chat chỉ chạy MỘT lượt tại một thời điểm.
 *
 * <h2>Vì sao cần, khi đã có {@link TurnAdmission}</h2>
 *
 * <p>{@code TurnAdmission} đếm theo NGƯỜI (tối đa 2 lượt) — đủ chỗ cho hai lượt cùng một phiên chạy
 * song song: bấm gửi hai lần với hai khoá idempotency khác nhau, hoặc client không gửi khoá. Khi đó
 * lượt B đã qua bước "có người thật đang cầm phiên không" trước khi lượt A mở phiếu, rồi B vẫn ghi câu
 * trả lời của trợ lý sau khi nhân viên tiếp quản; hai lượt đọc cùng một lịch sử và ghi đan xen (R-23).
 *
 * <h2>Theo từng instance</h2>
 *
 * <p>Khoá nằm trong bộ nhớ, như {@code TurnAdmission}: production chạy một replica. Nhiều replica thì
 * bước tiếp theo là {@code pg_try_advisory_xact_lock} theo {@code session_id}.
 */
@Component
public class SessionTurnLock {

    /** Tự dọn: phần tử bị xoá khi nhả — set lớn theo số phiên ĐANG chạy, không theo tổng số phiên. */
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    /** @return {@code false} khi phiên đã có một lượt đang chạy */
    public boolean tryAcquire(UUID sessionId) {
        return running.add(sessionId);
    }

    /** Luôn gọi trong {@code finally} — thiếu một lần nhả là phiên ấy bị khoá tới khi khởi động lại. */
    public void release(UUID sessionId) {
        running.remove(sessionId);
    }

    /** Số phiên đang chạy — cho test kiểm không rò. */
    int size() {
        return running.size();
    }
}
