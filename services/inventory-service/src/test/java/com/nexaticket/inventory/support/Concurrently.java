// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Chạy N việc thật sự cùng lúc.
 *
 * <p>Chỉ nộp N việc vào một thread pool là chưa đủ để chứng minh điều gì: việc đầu tiên thường xong
 * trước khi việc cuối cùng kịp bắt đầu, nên test "đồng thời" hoá ra chạy gần như tuần tự và sẽ xanh
 * kể cả khi code có lỗi tranh chấp. Hai latch dưới đây giữ mọi luồng lại tới khi tất cả đã sẵn
 * sàng, rồi thả một lượt.
 *
 * <p><b>Một luồng cho mỗi việc, không ít hơn.</b> Nếu pool nhỏ hơn số việc thì những việc chưa
 * được xếp lịch không bao giờ gọi {@code ready.countDown()}, và {@code ready.await()} treo vĩnh
 * viễn. Số luồng ở đây là số việc — chính vì thế mà tham số "số luồng" không tồn tại: nó là thứ
 * chỉ có thể đặt sai.
 *
 * <p>Số kết nối database đồng thời vẫn bị chặn bởi Hikari pool, không phải bởi số luồng — các
 * luồng thừa chỉ nằm chờ lấy connection, đúng như ở production khi tải vượt pool.
 */
public final class Concurrently {

    private Concurrently() {}

    /**
     * @return kết quả từng việc, cùng thứ tự với {@code jobs}
     */
    public static <T> List<Outcome<T>> run(List<Callable<T>> jobs) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(jobs.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Outcome<T>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(jobs.size())) {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (Callable<T> job : jobs) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return new Outcome<>(job.call(), null);
                    } catch (Exception e) {
                        return new Outcome<T>(null, e);
                    }
                }));
            }
            ready.await();
            go.countDown();
            for (Future<Outcome<T>> future : futures) {
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    results.add(new Outcome<>(null, e));
                }
            }
        }
        return results;
    }

    /**
     * @param value kết quả nếu việc chạy xong
     * @param error ngoại lệ nếu việc ném — giữ lại để test khẳng định được <b>lý do</b> thất bại,
     *     không chỉ là đếm số thất bại
     */
    public record Outcome<T>(T value, Exception error) {
        public boolean succeeded() {
            return error == null;
        }
    }

    public static <T> long successes(List<Outcome<T>> outcomes) {
        return outcomes.stream().filter(Outcome::succeeded).count();
    }

    /** Các mã lỗi nghiệp vụ đã gặp — dùng để khẳng định thất bại đúng lý do, không phải lỗi hệ thống. */
    public static <T> List<String> errorCodes(List<Outcome<T>> outcomes) {
        return outcomes.stream()
                .filter(o -> !o.succeeded())
                .map(o -> o.error() instanceof com.nexaticket.platform.web.error.ApiException api
                        ? api.errorCode().code()
                        : o.error().getClass().getSimpleName())
                .distinct()
                .sorted()
                .toList();
    }
}
