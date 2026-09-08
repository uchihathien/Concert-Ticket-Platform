// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.infrastructure.redis;

import com.nexaticket.inventory.domain.port.AvailabilityGate;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Cổng từ chối nhanh bằng Redis, chỉ cho vé ngồi.
 *
 * <p>Script Lua chạy nguyên tử trong Redis: <b>kiểm tra hết rồi mới ghi</b>. Nếu kiểm và ghi xen kẽ
 * thì một script có thể ghi được 3 trong 5 ghế rồi phát hiện ghế thứ 4 đã có người — để lại trạng
 * thái nửa vời mà không ai dọn.
 */
@Component
public class RedisAvailabilityGate implements AvailabilityGate {

    /**
     * Trả về danh sách key đã bị chiếm; rỗng nghĩa là chiếm thành công.
     *
     * <p>Trả về <b>tất cả</b> key đụng độ chứ không dừng ở cái đầu tiên: client tô đỏ được đúng
     * những ghế vừa mất thay vì bắt khách chọn lại từ đầu.
     */
    private static final RedisScript<List> ACQUIRE_ALL_OR_NONE = new DefaultRedisScript<>(
            """
            local taken = {}
            for i = 1, #KEYS do
              if redis.call('EXISTS', KEYS[i]) == 1 then
                taken[#taken + 1] = KEYS[i]
              end
            end
            if #taken > 0 then
              return taken
            end
            for i = 1, #KEYS do
              redis.call('SET', KEYS[i], ARGV[1], 'EX', tonumber(ARGV[2]))
            end
            return {}
            """,
            List.class);

    private final StringRedisTemplate redis;

    public RedisAvailabilityGate(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public List<UUID> tryAcquire(UUID eventSessionId, List<UUID> seatIds, UUID holdId, UUID userId, int ttlSeconds) {
        if (seatIds.isEmpty()) {
            return List.of();
        }
        List<String> keys = seatIds.stream().map(id -> key(eventSessionId, id)).toList();
        try {
            @SuppressWarnings("unchecked")
            List<String> taken =
                    redis.execute(ACQUIRE_ALL_OR_NONE, keys, holdId + ":" + userId, String.valueOf(ttlSeconds));
            return taken == null
                    ? List.of()
                    : taken.stream().map(RedisAvailabilityGate::seatIdOf).toList();
        } catch (RedisConnectionFailureException e) {
            throw new GateUnavailableException("Redis không sẵn sàng", e);
        } catch (DataAccessException e) {
            throw new GateUnavailableException("Lỗi Redis khi giữ chỗ", e);
        }
    }

    @Override
    public void release(UUID eventSessionId, List<UUID> seatIds) {
        if (seatIds.isEmpty()) {
            return;
        }
        try {
            redis.delete(seatIds.stream().map(id -> key(eventSessionId, id)).toList());
        } catch (DataAccessException e) {
            // Nhả là thao tác bù trừ, và key nào cũng có TTL. Redis chết ở đây chỉ khiến ghế
            // kẹt tới hết TTL — khó chịu, nhưng không sai sổ sách. Ném lên sẽ làm hỏng một
            // transaction vốn đã thành công, tệ hơn nhiều.
            org.slf4j.LoggerFactory.getLogger(RedisAvailabilityGate.class)
                    .warn("Không nhả được khoá Redis cho suất {}, chờ TTL", eventSessionId, e);
        }
    }

    /**
     * {@code hold:{sessionId}:seatId}.
     *
     * <p>Cặp ngoặc nhọn là hash tag của Redis Cluster: mọi key của cùng một suất diễn phải nằm cùng
     * một slot, nếu không script Lua nhiều key sẽ bị từ chối khi lên cluster. <b>Không được bỏ.</b>
     */
    private static String key(UUID eventSessionId, UUID seatId) {
        return "hold:{" + eventSessionId + "}:" + seatId;
    }

    private static UUID seatIdOf(String key) {
        return UUID.fromString(key.substring(key.lastIndexOf(':') + 1));
    }
}
