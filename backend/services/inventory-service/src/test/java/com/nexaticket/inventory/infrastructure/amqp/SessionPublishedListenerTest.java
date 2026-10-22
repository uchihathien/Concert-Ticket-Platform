// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.inventory.infrastructure.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.inventory.application.command.MaterializeSessionHandler;
import com.nexaticket.inventory.domain.port.SessionMaterializer;
import com.nexaticket.inventory.infrastructure.seed.DemoOccupancy;
import com.nexaticket.inventory.support.AmqpMessages;
import com.nexaticket.platform.idempotency.ProcessedEvents;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Consumer {@code session.published}: dịch payload của Catalog sang manifest của Inventory. */
class SessionPublishedListenerTest {

    private static final String QUEUE = "inventory.catalog.session-published";
    private static final UUID SESSION = UUID.fromString("00000000-0000-4000-a000-0000000000a1");
    private static final UUID EVENT = UUID.fromString("00000000-0000-4000-a000-0000000000e1");
    private static final UUID ORG = UUID.fromString("00000000-0000-4000-a000-0000000000f1");
    private static final UUID TYPE = UUID.fromString("00000000-0000-4000-a000-000000000011");

    private static final String VALID_PAYLOAD =
            """
            {
              "eventSessionId": "%s",
              "eventId": "%s",
              "organizationId": "%s",
              "salesOpenAt": "2026-10-01T00:00:00Z",
              "salesCloseAt": "2026-11-01T00:00:00Z",
              "limits": {"maxSeatedPerHold": 4, "maxStandingPerHold": 6, "maxUnitsPerHold": 8, "maxTicketsPerCustomer": 10},
              "seats": [
                {"seatCode": "A-1-1", "zoneCode": "A", "sectionLabel": "Khan dai A", "rowLabel": "1", "seatLabel": "1",
                 "posX": 10.5, "posY": 2, "ticketTypeId": "%s", "ticketTypeName": "Ve ngoi", "priceVnd": 1500000, "blocked": true},
                {"seatCode": "A-1-2", "zoneCode": "A", "sectionLabel": null, "ticketTypeId": "%s",
                 "ticketTypeName": "Ve ngoi", "priceVnd": 1500000}
              ],
              "standingBlocks": [
                {"zoneCode": "GA", "quantity": 3000, "ticketTypeId": "%s", "ticketTypeName": "Ve dung", "priceVnd": 800000}
              ]
            }
            """
                    .formatted(SESSION, EVENT, ORG, TYPE, TYPE, TYPE);

    private final MaterializeSessionHandler materialize = mock(MaterializeSessionHandler.class);
    private final ProcessedEvents processed = mock(ProcessedEvents.class);
    private final DemoOccupancy demo = mock(DemoOccupancy.class);

    private final SessionPublishedListener listener =
            new SessionPublishedListener(materialize, processed, demo, new ObjectMapper());

    @BeforeEach
    void setUp() {
        when(processed.markIfNew(anyString(), anyString())).thenReturn(true);
    }

    @Nested
    @DisplayName("Dịch payload")
    class DichPayload {

        @Test
        @DisplayName("Đủ mọi trường: manifest mang đúng suất, cửa bán, trần, ghế và khối vé đứng")
        void dich_du_truong() {
            when(materialize.handle(any())).thenReturn(3002);

            listener.onSessionPublished(AmqpMessages.event(UUID.randomUUID(), "session.published", VALID_PAYLOAD));

            ArgumentCaptor<SessionMaterializer.SessionManifest> captor =
                    ArgumentCaptor.forClass(SessionMaterializer.SessionManifest.class);
            verify(materialize).handle(captor.capture());
            var manifest = captor.getValue();
            assertThat(manifest.eventSessionId()).isEqualTo(SESSION);
            assertThat(manifest.eventId()).isEqualTo(EVENT);
            assertThat(manifest.organizationId()).isEqualTo(ORG);
            assertThat(manifest.salesOpenAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
            assertThat(manifest.salesCloseAt()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
            assertThat(manifest.maxSeatedPerHold()).isEqualTo(4);
            assertThat(manifest.maxStandingPerHold()).isEqualTo(6);
            assertThat(manifest.maxUnitsPerHold()).isEqualTo(8);
            assertThat(manifest.maxTicketsPerCustomer()).isEqualTo(10);
            assertThat(manifest.seats())
                    .containsExactly(
                            new SessionMaterializer.SeatLine(
                                    "A-1-1",
                                    "A",
                                    "Khan dai A",
                                    "1",
                                    "1",
                                    new BigDecimal("10.5"),
                                    new BigDecimal("2"),
                                    TYPE,
                                    "Ve ngoi",
                                    1_500_000L,
                                    true),
                            new SessionMaterializer.SeatLine(
                                    "A-1-2", "A", null, null, null, null, null, TYPE, "Ve ngoi", 1_500_000L, false));
            assertThat(manifest.standingBlocks())
                    .containsExactly(new SessionMaterializer.StandingBlock("GA", 3000, TYPE, "Ve dung", 800_000L));
        }
    }

    @Nested
    @DisplayName("Dữ liệu mẫu chỉ cho suất vừa dựng")
    class DuLieuMau {

        @Test
        @DisplayName("Suất vừa dựng: áp dữ liệu mẫu cho đúng suất và tổ chức")
        void suat_vua_dung() {
            when(materialize.handle(any())).thenReturn(3002);

            listener.onSessionPublished(AmqpMessages.event(UUID.randomUUID(), "session.published", VALID_PAYLOAD));

            verify(demo).applyTo(SESSION, ORG);
        }

        @Test
        @DisplayName("Suất đã có tồn kho (materialize trả 0): KHÔNG bán thêm chỗ mẫu vào suất có thể đã bán thật")
        void suat_da_co_ton_kho() {
            when(materialize.handle(any())).thenReturn(0);

            listener.onSessionPublished(AmqpMessages.event(UUID.randomUUID(), "session.published", VALID_PAYLOAD));

            verifyNoInteractions(demo);
        }
    }

    @Nested
    @DisplayName("Idempotent và message hỏng")
    class IdempotentVaMessageHong {

        @Test
        @DisplayName("Message trùng: không dựng lại")
        void message_trung() {
            UUID messageId = UUID.randomUUID();
            when(processed.markIfNew(QUEUE, messageId.toString())).thenReturn(false);

            listener.onSessionPublished(AmqpMessages.event(messageId, "session.published", VALID_PAYLOAD));

            verifyNoInteractions(materialize, demo);
        }

        @Test
        @DisplayName("Thân không phải JSON: ack và bỏ, không đánh dấu")
        void than_khong_phai_json() {
            assertThatCode(() -> listener.onSessionPublished(
                            AmqpMessages.event(UUID.randomUUID(), "session.published", "<xml/>")))
                    .doesNotThrowAnyException();

            verifyNoInteractions(materialize, processed);
        }

        @Test
        @DisplayName("Dựng tồn kho hỏng: ném lại để rollback cả dấu đã xử lý — message được giao lại")
        void dung_hong_thi_nem_lai() {
            when(materialize.handle(any())).thenThrow(new IllegalStateException("db down"));

            assertThatThrownBy(() -> listener.onSessionPublished(
                            AmqpMessages.event(UUID.randomUUID(), "session.published", VALID_PAYLOAD)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("db down");
            verifyNoInteractions(demo);
        }

        @Test
        @DisplayName("Payload thiếu khối limits: message rác vĩnh viễn — ack và bỏ, không ném để chặn queue")
        void thieu_limits_la_message_rac() {
            String payload = VALID_PAYLOAD.replaceAll("\"limits\": \\{[^}]*},", "");

            assertThatCode(() -> listener.onSessionPublished(
                            AmqpMessages.event(UUID.randomUUID(), "session.published", payload)))
                    .doesNotThrowAnyException();
            verify(materialize, never()).handle(any());
        }
    }
}
