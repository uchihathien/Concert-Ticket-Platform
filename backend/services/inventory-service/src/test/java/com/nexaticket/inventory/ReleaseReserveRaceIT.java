// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.nexaticket.inventory.application.command.PlaceHoldHandler;
import com.nexaticket.inventory.application.command.ReleaseHoldHandler;
import com.nexaticket.inventory.application.command.ReserveSeatsHandler;
import com.nexaticket.inventory.support.InventoryFixture;
import com.nexaticket.inventory.support.InventoryTestBase;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Khách bấm "bỏ giữ chỗ" đúng lúc saga checkout đang đặt chỗ.
 *
 * <p>{@link com.nexaticket.inventory.support.Concurrently} thả các luồng cùng lúc và để may rủi
 * quyết định ai chạy trước — đủ cho bài toán "200 người giành một ghế", nhưng không ép được một
 * thứ tự xen kẽ cụ thể. Ở đây thứ tự được ép bằng khoá hàng của chính PostgreSQL:
 *
 * <ol>
 *   <li>Saga đặt chỗ trong một transaction <b>chưa commit</b> — hàng {@code seat_holds} đang bị
 *       khoá, ghế đã RESERVED trong transaction đó.
 *   <li>Khách bỏ giữ chỗ: đọc thấy giữ chỗ vẫn ACTIVE (bản đã commit), rồi xếp hàng chờ khoá.
 *   <li>Saga commit. Lệnh bỏ giữ chỗ được đi tiếp — trên một nhận định đã cũ.
 * </ol>
 *
 * <p>Bất biến phải giữ: đặt chỗ còn RESERVED thì ghế phải còn RESERVED. Nếu ghế về AVAILABLE
 * trong khi Ordering vẫn giữ đơn, người tiếp theo mua được đúng những ghế đó và đơn kia vẫn thanh
 * toán được — bán trùng.
 */
class ReleaseReserveRaceIT extends InventoryTestBase {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    PlaceHoldHandler placeHold;

    @Autowired
    ReleaseHoldHandler releaseHold;

    @Autowired
    ReserveSeatsHandler reserveSeats;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    InventoryFixture fixture;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("Bỏ giữ chỗ xếp hàng sau lệnh đặt chỗ đang chạy: ghế của đơn đã đặt KHÔNG được về AVAILABLE")
    void bo_giu_cho_xep_hang_sau_dat_cho() throws Exception {
        var session = fixture.materialize(2, 0);
        UUID user = UUID.randomUUID();
        var hold = placeHold.handle(
                new PlaceHoldHandler.Command(session.id(), user, session.seatIds(), List.of(), List.of()));
        UUID orderId = UUID.randomUUID();

        CountDownLatch reservedButNotCommitted = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> saga = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                reserveSeats.handle(new ReserveSeatsHandler.Command(orderId, hold.holdId(), user));
                reservedButNotCommitted.countDown();
                awaitLatch(allowCommit);
            }));
            awaitLatch(reservedButNotCommitted);

            Future<?> customer = pool.submit(() -> releaseHold.handle(hold.holdId(), user));
            try {
                awaitSomeoneWaitingOnRowLock(customer);
            } finally {
                // Thả saga kể cả khi không dựng được thứ tự — nếu không, luồng của nó treo tới hết hạn.
                allowCommit.countDown();
            }
            saga.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            try {
                customer.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            } catch (ExecutionException rejected) {
                // Từ chối lệnh bỏ giữ chỗ là một kết quả hợp lệ — miễn là bất biến bên dưới còn đúng.
            }
        }

        assertThat(reservationStatus(orderId)).isEqualTo("RESERVED");
        assertThat(fixture.countByStatus(session.id(), "RESERVED"))
                .as("đặt chỗ còn RESERVED thì ghế phải còn RESERVED, không được trả về kho để bán lại")
                .isEqualTo(2);
        assertThat(fixture.countByStatus(session.id(), "AVAILABLE")).isZero();
        assertThat(fixture.countHolders(session.id())).isEqualTo(2);
    }

    /** Chờ tới khi PostgreSQL báo có một phiên đang đợi khoá hàng trong database này. */
    private void awaitSomeoneWaitingOnRowLock(Future<?> customer) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (true) {
            Integer waiting = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM pg_stat_activity
                     WHERE datname = current_database() AND wait_event_type = 'Lock'
                    """,
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            if (customer.isDone()) {
                // Lệnh bỏ giữ chỗ xong mà không phải chờ khoá: nó đã nhìn thấy trạng thái chưa
                // commit, tức là thứ tự xen kẽ cần dựng không xảy ra — test này không còn nghĩa.
                fail("Lệnh bỏ giữ chỗ không chờ khoá hàng của saga; không dựng được thứ tự xen kẽ");
            }
            if (System.nanoTime() > deadline) {
                fail("Không thấy phiên nào chờ khoá sau " + WAIT);
            }
            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("Hết thời gian chờ latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String reservationStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM seat_reservations WHERE order_id = ?", String.class, orderId);
    }
}
