// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.application.agent.AgentProperties;
import com.nexaticket.aichatbox.application.agent.TurnAdmission;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Trần đồng thời theo người dùng — chốt chặn mà giới hạn tần suất ở gateway không thấy. */
class TurnAdmissionTest {

    private static TurnAdmission admissionWithLimit(int perUser) {
        return new TurnAdmission(new AgentProperties(null, 0, 0, 0, 0, 0, 0, perUser));
    }

    @Test
    void cho_qua_dung_so_luot_da_khai() {
        TurnAdmission admission = admissionWithLimit(2);
        UUID user = UUID.randomUUID();

        assertThat(admission.tryAcquire(user)).isTrue();
        assertThat(admission.tryAcquire(user)).isTrue();
        assertThat(admission.tryAcquire(user)).isFalse();
    }

    @Test
    void nha_cho_roi_thi_vao_duoc_tiep() {
        TurnAdmission admission = admissionWithLimit(1);
        UUID user = UUID.randomUUID();
        admission.tryAcquire(user);

        admission.release(user);

        assertThat(admission.tryAcquire(user)).isTrue();
    }

    @Test
    void mot_nguoi_chiem_cho_khong_chan_nguoi_khac() {
        TurnAdmission admission = admissionWithLimit(1);
        UUID hungry = UUID.randomUUID();
        admission.tryAcquire(hungry);

        assertThat(admission.tryAcquire(UUID.randomUUID())).isTrue();
    }

    /**
     * Hai mươi luồng tranh cùng một người dùng: đúng {@code limit} luồng được vào, không hơn.
     *
     * <p>Nếu {@code tryAcquire} dùng get-rồi-put thay vì {@code compute}, test này đỏ không đều đặn
     * — đúng kiểu hỏng mà một chốt chặn đồng thời không được phép có.
     */
    @Test
    void dem_dung_khi_nhieu_luong_cung_vao() throws InterruptedException {
        TurnAdmission admission = admissionWithLimit(3);
        UUID user = UUID.randomUUID();
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(20);

        for (int i = 0; i < 20; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    if (admission.tryAcquire(user)) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(allowed).hasValue(3);
    }

    /** Không còn lượt nào thì khoá phải biến mất — nếu không, map lớn theo tổng số người từng chat. */
    @Test
    void khong_ro_ri_khoa_sau_khi_nha_het() {
        TurnAdmission admission = admissionWithLimit(2);
        UUID user = UUID.randomUUID();
        admission.tryAcquire(user);
        admission.tryAcquire(user);

        admission.release(user);
        admission.release(user);

        // Nhả thừa không được ném và không được làm âm bộ đếm.
        admission.release(user);
        assertThat(admission.tryAcquire(user)).isTrue();
    }
}
