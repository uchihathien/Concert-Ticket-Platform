// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexaticket.ticketing.domain.model.AssignmentWindow;
import com.nexaticket.ticketing.domain.model.AssignmentWindow.Session;
import com.nexaticket.ticketing.domain.model.AssignmentWindow.Status;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Hạn phân công soát vé: suất cuối trong phạm vi + 6 giờ. */
class AssignmentWindowTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final Instant A_START = Instant.parse("2026-12-01T12:00:00Z");
    private static final Instant B_START = Instant.parse("2026-12-02T12:00:00Z");

    private final List<Session> sessions = List.of(
            new Session(A, A_START, A_START.plusSeconds(3 * 3600)),
            // Suất B không khai giờ kết thúc: coi như kéo dài 6 giờ.
            new Session(B, B_START, null));

    @Test
    @DisplayName(
            "cả sự kiện: bắt đầu từ suất đầu, hết hạn 6 giờ sau suất cuối (suất không khai giờ kết thúc tính 6 giờ)")
    void ca_su_kien() {
        AssignmentWindow window = AssignmentWindow.of(sessions, null);

        assertThat(window.startsAt()).isEqualTo(A_START);
        assertThat(window.expiresAt()).isEqualTo(B_START.plusSeconds(12 * 3600));
    }

    @Test
    @DisplayName("một suất: chỉ tính suất đó")
    void mot_suat() {
        AssignmentWindow window = AssignmentWindow.of(sessions, A);

        assertThat(window.startsAt()).isEqualTo(A_START);
        assertThat(window.expiresAt()).isEqualTo(A_START.plusSeconds(9 * 3600));
    }

    @Test
    @DisplayName("trạng thái: sắp tới trước giờ đầu, đang diễn ra tới hết hạn (tính cả mốc), sau đó hết hạn")
    void trang_thai() {
        AssignmentWindow window = AssignmentWindow.of(sessions, A);

        assertThat(window.statusAt(A_START.minusSeconds(1))).isEqualTo(Status.UPCOMING);
        assertThat(window.statusAt(A_START)).isEqualTo(Status.ONGOING);
        assertThat(window.statusAt(window.expiresAt())).isEqualTo(Status.ONGOING);
        assertThat(window.statusAt(window.expiresAt().plusSeconds(1))).isEqualTo(Status.EXPIRED);
    }

    @Test
    @DisplayName("chưa có suất nào (gán trước, thêm suất sau) hoặc suất đã bị xoá: chưa thể hết hạn")
    void chua_co_suat() {
        assertThat(AssignmentWindow.of(List.of(), null).activeAt(Instant.MAX)).isTrue();
        assertThat(AssignmentWindow.of(sessions, UUID.randomUUID()).statusAt(A_START))
                .isEqualTo(Status.UPCOMING);
    }
}
