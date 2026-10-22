// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application.command;

import static com.nexaticket.payment.support.IntentFixtures.CLOCK;
import static com.nexaticket.payment.support.IntentFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.payment.domain.port.PaymentIntentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Lưới dọn intent quá hạn: một câu UPDATE có điều kiện, theo lô, mốc giờ từ Clock. */
@ExtendWith(MockitoExtension.class)
class ExpireIntentsJobTest {

    @Mock
    PaymentIntentRepository intents;

    @Test
    @DisplayName("Đóng theo mốc giờ của Clock, lô 200, trả đúng số repository báo đã đóng — không đọc rồi ghi")
    void dong_theo_moc_clock_theo_lo() {
        when(intents.expirePending(NOW, 200)).thenReturn(7);

        assertThat(new ExpireIntentsJob(intents, CLOCK).runOnce()).isEqualTo(7);

        // Không có lượt đọc nào trước câu UPDATE: đọc rồi ghi là cửa sổ để ghi đè một intent vừa nhận tiền.
        verify(intents).expirePending(NOW, 200);
        verifyNoMoreInteractions(intents);
    }
}
