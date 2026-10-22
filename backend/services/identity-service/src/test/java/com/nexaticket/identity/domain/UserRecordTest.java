// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.identity.domain.model.UserStatus;
import com.nexaticket.identity.domain.port.UserRepository.UserRecord;
import com.nexaticket.kernel.id.UserId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Hai câu hỏi mà mọi request đã đăng nhập tới identity đều đi qua: tài khoản còn dùng được không,
 * và token này có phát trước mốc thu hồi không.
 */
class UserRecordTest {

    private static final Instant CUTOFF = Instant.parse("2026-09-08T10:00:00Z");

    private static UserRecord user(UserStatus status, Instant tokensValidFrom) {
        return new UserRecord(
                UserId.of(UUID.randomUUID()), "sub", "a@example.com", "A", null, false, status, tokensValidFrom);
    }

    @Test
    @DisplayName("token phát trước mốc thu hồi bị từ chối, phát đúng mốc hoặc sau thì còn hiệu lực")
    void moc_thu_hoi() {
        UserRecord user = user(UserStatus.ACTIVE, CUTOFF);

        assertThat(user.tokenStillValid(CUTOFF.minusMillis(1))).isFalse();
        assertThat(user.tokenStillValid(CUTOFF)).isTrue();
        assertThat(user.tokenStillValid(CUTOFF.plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("chưa từng thu hồi thì mọi token còn hiệu lực")
    void chua_tung_thu_hoi() {
        assertThat(user(UserStatus.ACTIVE, null).tokenStillValid(Instant.EPOCH)).isTrue();
    }

    @Test
    @DisplayName("token không mang iat thì không bị khoá oan")
    void khong_co_iat() {
        assertThat(user(UserStatus.ACTIVE, CUTOFF).tokenStillValid(null)).isTrue();
    }

    @Test
    @DisplayName("chỉ trạng thái ACTIVE mới là tài khoản dùng được")
    void trang_thai() {
        assertThat(user(UserStatus.ACTIVE, null).isActive()).isTrue();
        assertThat(user(UserStatus.DISABLED, null).isActive()).isFalse();
        assertThat(user(null, null).isActive()).isFalse();
    }
}
