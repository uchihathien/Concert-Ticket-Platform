// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.aichatbox.application.agent.SessionTurnLock;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class SessionTurnLockTest {

    private final SessionTurnLock lock = new SessionTurnLock();

    private int size() throws Exception {
        Method size = SessionTurnLock.class.getDeclaredMethod("size");
        size.setAccessible(true);
        return (int) size.invoke(lock);
    }

    @Test
    void moi_phien_mot_nguoi_giu_nha_xong_lay_lai_duoc() throws Exception {
        UUID session = UUID.randomUUID();

        assertThat(lock.tryAcquire(session)).isTrue();
        assertThat(lock.tryAcquire(session)).isFalse();
        assertThat(lock.tryAcquire(UUID.randomUUID())).isTrue();
        lock.release(session);
        assertThat(lock.tryAcquire(session)).isTrue();
    }

    /** Không giữ khoá nào thì không còn gì trong bộ nhớ — không rò theo tổng số phiên từng chat. */
    @Test
    void nha_het_thi_rong() throws Exception {
        for (int i = 0; i < 1000; i++) {
            UUID s = UUID.randomUUID();
            lock.tryAcquire(s);
            lock.release(s);
        }

        assertThat(size()).isZero();
    }

    @Test
    void hai_luong_cung_phien_dung_mot_luong_thang() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 100; i++) {
                UUID session = UUID.randomUUID();
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> a = pool.submit(() -> {
                    start.await();
                    return lock.tryAcquire(session);
                });
                Future<Boolean> b = pool.submit(() -> {
                    start.await();
                    return lock.tryAcquire(session);
                });
                start.countDown();

                assertThat(a.get() ^ b.get())
                        .as("vòng %d: đúng một luồng thắng", i)
                        .isTrue();
                lock.release(session);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
