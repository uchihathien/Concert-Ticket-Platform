// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.realtime.application.AvailabilityUpdate;
import com.nexaticket.realtime.application.SessionRegistry;
import com.nexaticket.realtime.application.UpdateCoalescer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gom thông báo và quản lý kết nối.
 *
 * <p>Cả hai lớp được kiểm ở đây đều cố ý không phụ thuộc API WebSocket của Spring, nên chúng test
 * được như code thuần — trong khi vẫn là chỗ chứa toàn bộ hành vi đáng lo của service này: gom
 * message, và không rò rỉ kết nối.
 */
class FanoutTest {

    @Test
    @DisplayName("Hàng trăm thay đổi trong một cửa sổ gom thành ĐÚNG MỘT thông báo mỗi suất")
    void gom_thanh_mot_thong_bao() {
        // Một suất diễn hot nhận hàng trăm thay đổi mỗi giây trong đợt mở bán. Đẩy thẳng từng
        // cái xuống 10.000 kết nối là hàng triệu message mỗi giây và trình duyệt client đơ.
        var coalescer = new UpdateCoalescer();
        UUID session = UUID.randomUUID();
        for (int i = 1; i <= 500; i++) {
            coalescer.accept(new AvailabilityUpdate(session, i));
        }

        List<AvailabilityUpdate> drained = coalescer.drain();

        assertThat(drained).hasSize(1);
        // Giữ version LỚN NHẤT: các version cũ không mang thêm thông tin gì, vì client chỉ dùng
        // version để phát hiện mình đã lạc hậu.
        assertThat(drained.get(0).version()).isEqualTo(500);
    }

    @Test
    @DisplayName("Mỗi suất diễn một thông báo riêng, không trộn vào nhau")
    void moi_suat_mot_thong_bao() {
        var coalescer = new UpdateCoalescer();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        coalescer.accept(new AvailabilityUpdate(a, 1));
        coalescer.accept(new AvailabilityUpdate(b, 7));
        coalescer.accept(new AvailabilityUpdate(a, 3));

        assertThat(coalescer.drain()).hasSize(2);
        assertThat(coalescer.drain()).isEmpty();
    }

    @Test
    @DisplayName("Nhận thay đổi từ nhiều luồng cùng lúc không mất và không nhân đôi")
    void nhan_tu_nhieu_luong() throws Exception {
        // Listener AMQP chạy nhiều luồng; nếu gom sai thì hoặc mất cập nhật (client kẹt ở sơ đồ
        // cũ) hoặc nhân đôi (client refetch thừa).
        var coalescer = new UpdateCoalescer();
        List<UUID> sessions = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            sessions.add(UUID.randomUUID());
        }

        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < 16; i++) {
                pool.submit(() -> {
                    go.await();
                    for (int round = 1; round <= 100; round++) {
                        for (UUID session : sessions) {
                            coalescer.accept(new AvailabilityUpdate(session, round));
                        }
                    }
                    return null;
                });
            }
            go.countDown();
        }

        assertThat(coalescer.drain()).hasSize(20);
    }

    @Test
    @DisplayName("Đóng kết nối gỡ nó khỏi mọi suất diễn, không để lại rác")
    void dong_ket_noi_khong_de_lai_rac() {
        // Quên gỡ là rò rỉ bộ nhớ tăng dần theo số lần client mất mạng — thứ chỉ lộ ra sau
        // nhiều ngày chạy, đúng lúc không ai muốn debug.
        var registry = new SessionRegistry<String>(5);
        UUID session = UUID.randomUUID();
        registry.subscribe("conn-1", session);
        registry.subscribe("conn-2", session);

        registry.remove("conn-1");

        assertThat(registry.connectionCount(session)).isEqualTo(1);
        assertThat(registry.totalConnections()).isEqualTo(1);

        registry.remove("conn-2");
        assertThat(registry.connectionCount(session)).isZero();
        assertThat(registry.totalConnections()).isZero();
    }

    @Test
    @DisplayName("Một kết nối không theo dõi được quá số suất cho phép")
    void chan_dang_ky_qua_nhieu() {
        // Không có trần thì một client có thể đăng ký cả sàn diễn và biến mình thành ống dẫn
        // toàn bộ lưu lượng của hệ thống.
        var registry = new SessionRegistry<String>(2);

        assertThat(registry.subscribe("conn", UUID.randomUUID())).isTrue();
        assertThat(registry.subscribe("conn", UUID.randomUUID())).isTrue();
        assertThat(registry.subscribe("conn", UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("Đăng ký lại cùng một suất không tính thêm vào trần")
    void dang_ky_lai_khong_tinh_them() {
        var registry = new SessionRegistry<String>(1);
        UUID session = UUID.randomUUID();

        assertThat(registry.subscribe("conn", session)).isTrue();
        assertThat(registry.subscribe("conn", session)).isTrue();
        assertThat(registry.connectionCount(session)).isEqualTo(1);
    }

    @Test
    @DisplayName("Chỉ thuê bao của đúng suất diễn nhận được thông báo")
    void chi_dung_thue_bao_nhan_duoc() {
        var registry = new SessionRegistry<String>(5);
        UUID watched = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        registry.subscribe("conn-1", watched);
        registry.subscribe("conn-2", other);

        List<String> notified = new ArrayList<>();
        registry.forEachSubscriber(watched, notified::add);

        assertThat(notified).containsExactly("conn-1");
    }
}
