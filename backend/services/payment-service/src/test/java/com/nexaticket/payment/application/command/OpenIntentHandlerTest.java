// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.payment.application.command;

import static com.nexaticket.payment.support.IntentFixtures.AMOUNT;
import static com.nexaticket.payment.support.IntentFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexaticket.payment.application.PaymentErrorCode;
import com.nexaticket.payment.application.PaymentProperties;
import com.nexaticket.payment.domain.model.PaymentIntent;
import com.nexaticket.payment.domain.port.PaymentIntentRepository;
import com.nexaticket.payment.domain.port.PayosGateway;
import com.nexaticket.payment.support.IntentFixtures;
import com.nexaticket.platform.web.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Mở yêu cầu thanh toán — idempotent theo đơn, gọi payOS trước rồi mới ghi. */
@ExtendWith(MockitoExtension.class)
class OpenIntentHandlerTest {

    private static final long CODE = 1_000_123L;
    private static final Instant EXPIRES = NOW.plus(Duration.ofMinutes(15));

    @Mock
    PaymentIntentRepository intents;

    @Mock
    PayosGateway payos;

    OpenIntentHandler handler;

    UUID orderId;
    UUID organizationId;

    @BeforeEach
    void setUp() {
        PaymentProperties properties = new PaymentProperties(
                false,
                new PaymentProperties.Payos(
                        null, "client", "api", "checksum", "https://nexaticket.test/", null, null, null));
        handler = new OpenIntentHandler(intents, payos, properties);
        orderId = UUID.randomUUID();
        organizationId = UUID.randomUUID();
    }

    private OpenIntentHandler.Command command() {
        return new OpenIntentHandler.Command(orderId, organizationId, AMOUNT, EXPIRES);
    }

    @Nested
    @DisplayName("Đường thường")
    class DuongThuong {

        @Test
        @DisplayName("Đơn mới: cấp orderCode, gửi payOS đúng số tiền của đơn, mô tả 9 ký tự, URL quay về trang đơn")
        void don_moi_tao_link_dung_noi_dung() {
            when(intents.findByOrder(orderId)).thenReturn(Optional.empty());
            when(intents.nextOrderCode()).thenReturn(CODE);
            when(payos.createPaymentLink(any())).thenReturn(IntentFixtures.link(CODE, AMOUNT));
            when(intents.insertIfAbsent(any())).thenAnswer(inv -> inv.getArgument(0));

            OpenIntentHandler.Result result = handler.handle(command());

            ArgumentCaptor<PayosGateway.NewLink> sent = ArgumentCaptor.forClass(PayosGateway.NewLink.class);
            verify(payos).createPaymentLink(sent.capture());
            assertThat(sent.getValue().orderCode()).isEqualTo(CODE);
            assertThat(sent.getValue().amountVnd()).isEqualTo(AMOUNT);
            assertThat(sent.getValue().description()).isEqualTo("NT1000123").hasSize(9);
            assertThat(sent.getValue().expiresAt()).isEqualTo(EXPIRES);
            // Dấu "/" thừa ở web-base-url đã bị cắt: không có "//orders".
            assertThat(sent.getValue().returnUrl()).isEqualTo("https://nexaticket.test/orders/" + orderId + "/pay");
            assertThat(sent.getValue().cancelUrl()).isEqualTo(sent.getValue().returnUrl());

            assertThat(result.paymentReference()).isEqualTo("NT1000123");
            assertThat(result.checkoutUrl()).isEqualTo("https://pay.payos.vn/web/link-" + CODE);
            assertThat(result.bankAccountNumber()).isEqualTo("V3CAS" + CODE);
            assertThat(result.bankBin()).isEqualTo("970422");
            assertThat(result.vietQrPayload()).contains("V3CAS" + CODE);
        }

        @Test
        @DisplayName("Intent ghi xuống mang đúng tổ chức, số tiền và hạn của lệnh")
        void intent_ghi_xuong_dung_du_lieu() {
            when(intents.findByOrder(orderId)).thenReturn(Optional.empty());
            when(intents.nextOrderCode()).thenReturn(CODE);
            when(payos.createPaymentLink(any())).thenReturn(IntentFixtures.link(CODE, AMOUNT));
            when(intents.insertIfAbsent(any())).thenAnswer(inv -> inv.getArgument(0));

            handler.handle(command());

            ArgumentCaptor<PaymentIntent> stored = ArgumentCaptor.forClass(PaymentIntent.class);
            verify(intents).insertIfAbsent(stored.capture());
            assertThat(stored.getValue().orderId()).isEqualTo(orderId);
            assertThat(stored.getValue().organizationId()).isEqualTo(organizationId);
            assertThat(stored.getValue().amountVnd()).isEqualTo(AMOUNT);
            assertThat(stored.getValue().expiresAt()).isEqualTo(EXPIRES);
            assertThat(stored.getValue().isPending()).isTrue();
        }
    }

    @Nested
    @DisplayName("Idempotent")
    class Idempotent {

        @Test
        @DisplayName("Đơn đã có intent: trả đúng link cũ, KHÔNG gọi payOS, KHÔNG cấp orderCode mới")
        void don_da_co_intent() {
            PaymentIntent existing = IntentFixtures.pending(orderId, 1_000_001L);
            when(intents.findByOrder(orderId)).thenReturn(Optional.of(existing));

            OpenIntentHandler.Result result = handler.handle(command());

            assertThat(result.paymentReference()).isEqualTo("NT1000001");
            assertThat(result.checkoutUrl()).isEqualTo(existing.checkoutUrl());
            verifyNoInteractions(payos);
            verify(intents, never()).nextOrderCode();
            verify(intents, never()).insertIfAbsent(any());
        }

        @Test
        @DisplayName("Đơn đã có intent với số tiền KHÁC: vẫn trả link cũ — số tiền lấy từ intent, không từ lệnh")
        void lenh_lap_lai_voi_so_tien_khac() {
            // Lần gọi lại sau timeout không được đổi số tiền khách đang nhìn trên mã QR.
            PaymentIntent existing = IntentFixtures.pending(orderId, 1_000_001L);
            when(intents.findByOrder(orderId)).thenReturn(Optional.of(existing));

            OpenIntentHandler.Result result =
                    handler.handle(new OpenIntentHandler.Command(orderId, organizationId, 1L, EXPIRES));

            assertThat(result.vietQrPayload()).isEqualTo(existing.vietQrPayload());
            verifyNoInteractions(payos);
        }

        @Test
        @DisplayName("Thua cuộc đua mở song song: trả intent đã thắng và ĐÓNG link mồ côi vừa tạo")
        void thua_cuoc_dua_thi_dong_link_mo_coi() {
            PaymentIntent winner = IntentFixtures.pending(orderId, 1_000_001L);
            when(intents.findByOrder(orderId)).thenReturn(Optional.empty());
            when(intents.nextOrderCode()).thenReturn(CODE);
            when(payos.createPaymentLink(any())).thenReturn(IntentFixtures.link(CODE, AMOUNT));
            when(intents.insertIfAbsent(any())).thenReturn(winner);

            OpenIntentHandler.Result result = handler.handle(command());

            assertThat(result.paymentReference()).isEqualTo("NT1000001");
            // Đóng đúng link VỪA TẠO, không phải link của intent đã thắng — đóng nhầm là khoá cửa khách.
            verify(payos).cancelPaymentLink(CODE, "Đơn đã có link thanh toán khác");
            verify(payos, never()).cancelPaymentLink(1_000_001L, "Đơn đã có link thanh toán khác");
        }

        @Test
        @DisplayName("Không đóng được link mồ côi: checkout vẫn thành công với intent đã thắng")
        void dong_link_mo_coi_hong_van_thanh_cong() {
            PaymentIntent winner = IntentFixtures.pending(orderId, 1_000_001L);
            when(intents.findByOrder(orderId)).thenReturn(Optional.empty());
            when(intents.nextOrderCode()).thenReturn(CODE);
            when(payos.createPaymentLink(any())).thenReturn(IntentFixtures.link(CODE, AMOUNT));
            when(intents.insertIfAbsent(any())).thenReturn(winner);
            doThrow(new PayosGateway.Unavailable("timeout", null))
                    .when(payos)
                    .cancelPaymentLink(anyLong(), anyString());

            OpenIntentHandler.Result result = handler.handle(command());

            assertThat(result.paymentReference()).isEqualTo("NT1000001");
        }
    }

    @Nested
    @DisplayName("Lỗi từ payOS")
    class LoiTuPayos {

        @Test
        @DisplayName("payOS không phản hồi: 503 PAYOS_UNAVAILABLE, không ghi intent nào")
        void payos_khong_phan_hoi() {
            when(intents.findByOrder(orderId)).thenReturn(Optional.empty());
            when(intents.nextOrderCode()).thenReturn(CODE);
            when(payos.createPaymentLink(any())).thenThrow(new PayosGateway.Unavailable("timeout", null));

            assertThatThrownBy(() -> handler.handle(command()))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(PaymentErrorCode.PAYOS_UNAVAILABLE));
            verify(intents, never()).insertIfAbsent(any());
        }

        @Test
        @DisplayName("payOS từ chối: 502 PAYOS_REJECTED, không ghi intent nào")
        void payos_tu_choi() {
            when(intents.findByOrder(orderId)).thenReturn(Optional.empty());
            when(intents.nextOrderCode()).thenReturn(CODE);
            when(payos.createPaymentLink(any())).thenThrow(new PayosGateway.Rejected("231", "orderCode đã tồn tại"));

            assertThatThrownBy(() -> handler.handle(command())).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.errorCode()).isEqualTo(PaymentErrorCode.PAYOS_REJECTED);
                assertThat(e.errorCode().httpStatus()).isEqualTo(502);
            });
            verify(intents, never()).insertIfAbsent(any());
        }

        @Test
        @DisplayName("payOS ghi số tiền khác đơn trên link: không ghi intent nào")
        void payos_ghi_sai_so_tien() {
            when(intents.findByOrder(orderId)).thenReturn(Optional.empty());
            when(intents.nextOrderCode()).thenReturn(CODE);
            when(payos.createPaymentLink(any())).thenReturn(IntentFixtures.link(CODE, AMOUNT - 1));

            assertThatThrownBy(() -> handler.handle(command())).isInstanceOf(IllegalArgumentException.class);
            verify(intents, never()).insertIfAbsent(any());
        }

        @Test
        @DisplayName("Sequence vượt trần 9.999.999: nổ ngay, KHÔNG gọi payOS với mô tả 10 ký tự")
        void sequence_vuot_tran() {
            when(intents.findByOrder(orderId)).thenReturn(Optional.empty());
            when(intents.nextOrderCode()).thenReturn(10_000_000L);

            assertThatThrownBy(() -> handler.handle(command())).isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(payos);
        }
    }
}
