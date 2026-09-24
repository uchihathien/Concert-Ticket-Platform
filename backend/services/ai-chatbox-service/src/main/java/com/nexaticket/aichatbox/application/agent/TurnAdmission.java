// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application.agent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Trần số lượt chat <b>đang chạy</b> của mỗi người dùng.
 *
 * <h2>Khác với giới hạn tần suất ở gateway, và không thay thế nó</h2>
 *
 * <p>Gateway đếm <i>số request mỗi giây</i>. Cái đó chặn được kẻ bắn 1.000 request/giây, nhưng
 * không chặn được thứ nguy hiểm hơn ở đây: 200 request <b>chậm</b> mở song song. Mỗi lượt chat giữ
 * một luồng trong nhiều chục giây, nên 200 request nằm hoàn toàn trong hạn mức 50 rps vẫn chiếm
 * sạch năng lực của service — bằng một tài khoản hợp lệ, không cần công cụ gì.
 *
 * <p>Nên đây đo <b>đồng thời</b>, không đo tần suất. Hai chốt chặn khác nhau cho hai kiểu tấn công
 * khác nhau, và bỏ cái nào cũng để hở đúng phần cái kia không thấy.
 *
 * <h2>Theo từng instance, và đó là đủ</h2>
 *
 * <p>Bộ đếm nằm trong bộ nhớ nên với N replica, một người dùng về lý thuyết được N lần trần này.
 * Chấp nhận có chủ đích: mục đích của nó là bảo vệ <b>pool luồng của chính instance này</b> khỏi bị
 * một người chiếm hết, và một bộ đếm dùng chung qua Redis sẽ thêm một lời gọi mạng cùng một điểm
 * hỏng mới vào đúng đường đi nóng nhất — để đổi lấy độ chính xác mà bài toán không cần. Trần chi
 * phí theo người dùng là bài toán khác và nó thuộc về lớp khác.
 */
@Component
public class TurnAdmission {

    /**
     * Tự dọn: khoá bị xoá khi người dùng không còn lượt nào chạy.
     *
     * <p>Không dọn thì map lớn dần theo <b>tổng số người từng chat</b>, không theo số người đang
     * chat — một rò rỉ bộ nhớ chậm mà không có gì chỉ ra. {@code compute} và {@code computeIfPresent}
     * chạy nguyên tử trên cùng một khoá, nên đếm và dọn không cần khoá riêng.
     */
    private final ConcurrentMap<UUID, Integer> inFlight = new ConcurrentHashMap<>();

    private final int maxPerUser;

    public TurnAdmission(AgentProperties properties) {
        this.maxPerUser = properties.maxConcurrentTurnsPerUser();
    }

    /** @return {@code false} khi người này đã có đủ số lượt đang chạy */
    public boolean tryAcquire(UUID userId) {
        AtomicBoolean acquired = new AtomicBoolean(false);
        inFlight.compute(userId, (key, current) -> {
            int running = current == null ? 0 : current;
            if (running >= maxPerUser) {
                return current;
            }
            acquired.set(true);
            return running + 1;
        });
        return acquired.get();
    }

    /** Luôn gọi trong {@code finally} — thiếu một lần nhả là người ấy mất chỗ vĩnh viễn. */
    public void release(UUID userId) {
        inFlight.computeIfPresent(userId, (key, current) -> current <= 1 ? null : current - 1);
    }
}
