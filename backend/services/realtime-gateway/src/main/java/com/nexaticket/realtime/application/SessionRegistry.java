// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.realtime.application;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * Ai đang xem suất diễn nào.
 *
 * <p>Chỉ nằm trong bộ nhớ, và đó là toàn bộ lý do service này tách khỏi inventory-service: 10.000
 * kết nối WebSocket tốn RAM và file descriptor nhưng gần như không tốn CPU, còn inventory-service
 * thì ngược lại. Gộp chung buộc phải scale cả hai theo chiều xấu nhất của cả hai (services.md §4).
 *
 * <p>Mất sạch bản đồ này khi restart là chấp nhận được: client mất kết nối sẽ tự nối lại và fetch
 * lại sơ đồ, đúng như khi mạng chập chờn.
 *
 * @param <T> kiểu kết nối, để lớp này không phụ thuộc API WebSocket của Spring và test được
 */
public class SessionRegistry<T> {

    private final ConcurrentMap<UUID, Set<T>> byEventSession = new ConcurrentHashMap<>();

    /** Số suất diễn tối đa một kết nối được theo dõi — chặn client đăng ký cả sàn diễn. */
    private final int maxSessionsPerConnection;

    private final ConcurrentMap<T, Set<UUID>> sessionsOfConnection = new ConcurrentHashMap<>();

    public SessionRegistry(int maxSessionsPerConnection) {
        this.maxSessionsPerConnection = maxSessionsPerConnection;
    }

    /** @return false nếu kết nối đã theo dõi quá số suất cho phép */
    public boolean subscribe(T connection, UUID eventSessionId) {
        Set<UUID> subscribed = sessionsOfConnection.computeIfAbsent(connection, key -> ConcurrentHashMap.newKeySet());
        if (!subscribed.contains(eventSessionId) && subscribed.size() >= maxSessionsPerConnection) {
            return false;
        }
        subscribed.add(eventSessionId);
        byEventSession
                .computeIfAbsent(eventSessionId, key -> ConcurrentHashMap.newKeySet())
                .add(connection);
        return true;
    }

    /**
     * Gỡ kết nối khỏi mọi suất diễn.
     *
     * <p>Phải gọi ở cả đường đóng bình thường lẫn đường lỗi. Quên một đường là rò rỉ bộ nhớ tăng
     * dần theo số lần client mất mạng — thứ chỉ lộ ra sau nhiều ngày chạy.
     */
    public void remove(T connection) {
        Set<UUID> subscribed = sessionsOfConnection.remove(connection);
        if (subscribed == null) {
            return;
        }
        for (UUID eventSessionId : subscribed) {
            Set<T> connections = byEventSession.get(eventSessionId);
            if (connections != null) {
                connections.remove(connection);
                // Dọn key rỗng: không dọn thì bản đồ phình theo tổng số suất diễn từng được xem,
                // không phải theo số suất đang được xem.
                if (connections.isEmpty()) {
                    byEventSession.remove(eventSessionId, connections);
                }
            }
        }
    }

    public void forEachSubscriber(UUID eventSessionId, Consumer<T> action) {
        Set<T> connections = byEventSession.get(eventSessionId);
        if (connections != null) {
            connections.forEach(action);
        }
    }

    public int connectionCount(UUID eventSessionId) {
        Set<T> connections = byEventSession.get(eventSessionId);
        return connections == null ? 0 : connections.size();
    }

    public int totalConnections() {
        return sessionsOfConnection.size();
    }
}
