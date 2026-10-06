// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexaticket.aichatbox.interfaces.rest.ChatIdempotency;
import com.nexaticket.aichatbox.interfaces.rest.SupportChatController;
import com.nexaticket.aichatbox.support.AiChatboxTestBase;
import com.nexaticket.platform.web.error.ApiException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Chống gửi trùng cho lượt chat.
 *
 * <p>Chạy trên database thật vì cả cơ chế nằm ở một ràng buộc UNIQUE: {@code tryBegin} dựa vào
 * {@code ON CONFLICT DO NOTHING} trên {@code (user_id, idem_key)}. Test này cũng là phép kiểm rằng
 * migration platform V0002 thật sự chạy được trên database của service — nó mang số hiệu thấp hơn
 * V0100/V0101 nên cần {@code flyway.out-of-order}.
 */
class ChatIdempotencyIT extends AiChatboxTestBase {

    @Autowired
    ChatIdempotency idempotency;

    private static SupportChatController.AskResponse reply(String answer) {
        return new SupportChatController.AskResponse(UUID.randomUUID(), answer, List.of());
    }

    @Test
    void lan_dau_thi_duoc_chay() {
        assertThat(idempotency.beginOrReplay(UUID.randomUUID(), "key-1", UUID.randomUUID(), "vé của mình đâu"))
                .isEmpty();
    }

    @Test
    void gui_lai_dung_cau_hoi_do_thi_nhan_lai_cau_tra_loi_cu() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        idempotency.beginOrReplay(userId, key, sessionId, "vé của mình đâu");
        idempotency.complete(userId, key, reply("Vé của bạn đã phát hành rồi nhé."));

        var replay = idempotency.beginOrReplay(userId, key, sessionId, "vé của mình đâu");

        // Không gọi mô hình lần nữa — đó là toàn bộ mục đích: một lần gửi lại vì mạng chập không
        // được thành một lần tính tiền nữa.
        assertThat(replay).isPresent();
        assertThat(replay.orElseThrow().answer()).isEqualTo("Vé của bạn đã phát hành rồi nhé.");
    }

    @Test
    void cung_khoa_nhung_khac_noi_dung_thi_409() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        idempotency.beginOrReplay(userId, key, sessionId, "câu hỏi thứ nhất");
        idempotency.complete(userId, key, reply("trả lời thứ nhất"));

        // Trả về câu trả lời cũ cho một câu hỏi mới là kiểu hỏng tệ nhất: im lặng và sai.
        assertThatThrownBy(() -> idempotency.beginOrReplay(userId, key, sessionId, "câu hỏi thứ HAI"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("đã dùng cho một câu hỏi khác");
    }

    @Test
    void luot_truoc_chua_xong_thi_bao_dang_xu_ly() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        idempotency.beginOrReplay(userId, key, sessionId, "vé của mình đâu");

        assertThatThrownBy(() -> idempotency.beginOrReplay(userId, key, sessionId, "vé của mình đâu"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("đang được xử lý");
    }

    @Test
    void hong_thi_nha_khoa_de_gui_lai_duoc_ngay() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        idempotency.beginOrReplay(userId, key, sessionId, "vé của mình đâu");

        idempotency.release(userId, key);

        // Không nhả thì một lần 503 tạm thời khoá cứng đúng câu hỏi ấy mãi mãi.
        assertThat(idempotency.beginOrReplay(userId, key, sessionId, "vé của mình đâu"))
                .isEmpty();
    }

    @Test
    void khoa_tach_theo_nguoi_dung() {
        UUID sessionId = UUID.randomUUID();
        String key = "key-dung-chung";
        idempotency.beginOrReplay(UUID.randomUUID(), key, sessionId, "câu hỏi");

        // Hai người cùng sinh ra một chuỗi khoá không được chặn nhau.
        assertThat(idempotency.beginOrReplay(UUID.randomUUID(), key, sessionId, "câu hỏi"))
                .isEmpty();
    }
}
