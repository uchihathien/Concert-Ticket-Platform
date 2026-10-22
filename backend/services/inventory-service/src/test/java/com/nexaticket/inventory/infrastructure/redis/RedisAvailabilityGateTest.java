// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.inventory.domain.port.AvailabilityGate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Phần Java của cổng Redis: dựng key, đọc kết quả script, dịch lỗi.
 *
 * <p>Tính nguyên tử của script Lua thì chỉ Redis thật chứng minh được — xem các IT đồng thời. Ở
 * đây kiểm những thứ sai được ở phía Java mà không cần Redis.
 */
class RedisAvailabilityGateTest {

    private static final UUID SESSION = UUID.fromString("00000000-0000-4000-a000-0000000000a1");
    private static final UUID HOLD = UUID.fromString("00000000-0000-4000-a000-0000000000c1");
    private static final UUID USER = UUID.fromString("00000000-0000-4000-a000-0000000000b1");
    private static final UUID SEAT_1 = UUID.fromString("00000000-0000-4000-a000-000000000001");
    private static final UUID SEAT_2 = UUID.fromString("00000000-0000-4000-a000-000000000002");

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisAvailabilityGate gate = new RedisAvailabilityGate(redis);

    @Nested
    @DisplayName("Chiếm chỗ")
    class ChiemCho {

        @Test
        @DisplayName("Key mang hash tag của suất — mọi key của một suất cùng slot trên Redis Cluster")
        @SuppressWarnings({"unchecked", "rawtypes"})
        void key_co_hash_tag() {
            ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
            when(redis.execute(any(RedisScript.class), keys.capture(), any(), any()))
                    .thenReturn(List.of());

            List<UUID> taken = gate.tryAcquire(SESSION, List.of(SEAT_1, SEAT_2), HOLD, USER, 600);

            assertThat(taken).isEmpty();
            assertThat(keys.getValue())
                    .containsExactly("hold:{" + SESSION + "}:" + SEAT_1, "hold:{" + SESSION + "}:" + SEAT_2);
        }

        @Test
        @DisplayName("Giá trị key là holdId:userId, TTL truyền dạng chuỗi giây")
        @SuppressWarnings("unchecked")
        void gia_tri_va_ttl() {
            when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(List.of());

            gate.tryAcquire(SESSION, List.of(SEAT_1), HOLD, USER, 600);

            verify(redis).execute(any(RedisScript.class), anyList(), eq(HOLD + ":" + USER), eq("600"));
        }

        @Test
        @DisplayName("Script trả các key đã bị chiếm: đọc ngược ra đúng id ghế")
        @SuppressWarnings("unchecked")
        void doc_nguoc_ghe_bi_chiem() {
            when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                    .thenReturn(List.of("hold:{" + SESSION + "}:" + SEAT_2));

            assertThat(gate.tryAcquire(SESSION, List.of(SEAT_1, SEAT_2), HOLD, USER, 600))
                    .containsExactly(SEAT_2);
        }

        @Test
        @DisplayName("Script trả null: coi như chiếm thành công, không NullPointerException")
        @SuppressWarnings("unchecked")
        void script_tra_null() {
            when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(null);

            assertThat(gate.tryAcquire(SESSION, List.of(SEAT_1), HOLD, USER, 600))
                    .isEmpty();
        }

        @Test
        @DisplayName("Danh sách ghế rỗng: không gọi Redis")
        void danh_sach_rong() {
            assertThat(gate.tryAcquire(SESSION, List.of(), HOLD, USER, 600)).isEmpty();

            verifyNoInteractions(redis);
        }
    }

    @Nested
    @DisplayName("Redis không sẵn sàng")
    class RedisChet {

        @Test
        @DisplayName("Mất kết nối: GateUnavailableException — tầng trên trả 503, không fallback")
        @SuppressWarnings("unchecked")
        void mat_ket_noi() {
            when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                    .thenThrow(new RedisConnectionFailureException("refused"));

            assertThatThrownBy(() -> gate.tryAcquire(SESSION, List.of(SEAT_1), HOLD, USER, 600))
                    .isInstanceOf(AvailabilityGate.GateUnavailableException.class)
                    .hasCauseInstanceOf(RedisConnectionFailureException.class);
        }

        @Test
        @DisplayName("Lỗi Redis khác (script hỏng, timeout): cũng là GateUnavailableException")
        @SuppressWarnings("unchecked")
        void loi_redis_khac() {
            when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                    .thenThrow(new RedisSystemException("NOSCRIPT", new RuntimeException()));

            assertThatThrownBy(() -> gate.tryAcquire(SESSION, List.of(SEAT_1), HOLD, USER, 600))
                    .isInstanceOf(AvailabilityGate.GateUnavailableException.class);
        }
    }

    @Nested
    @DisplayName("Nhả chỗ")
    class NhaCho {

        @Test
        @DisplayName("Xoá đúng các key của suất")
        @SuppressWarnings({"unchecked", "rawtypes"})
        void xoa_dung_key() {
            gate.release(SESSION, List.of(SEAT_1, SEAT_2));

            ArgumentCaptor<java.util.Collection> keys = ArgumentCaptor.forClass(java.util.Collection.class);
            verify(redis).delete(keys.capture());
            assertThat(keys.getValue())
                    .containsExactly("hold:{" + SESSION + "}:" + SEAT_1, "hold:{" + SESSION + "}:" + SEAT_2);
        }

        @Test
        @DisplayName("Redis chết lúc nhả: nuốt lỗi — không làm hỏng transaction đã thành công, key tự hết TTL")
        @SuppressWarnings("unchecked")
        void redis_chet_luc_nha() {
            when(redis.delete(anyCollection())).thenThrow(new RedisConnectionFailureException("refused"));

            assertThatCode(() -> gate.release(SESSION, List.of(SEAT_1))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Danh sách rỗng: không gọi Redis")
        void nha_rong() {
            gate.release(SESSION, List.of());

            verifyNoInteractions(redis);
        }
    }
}
