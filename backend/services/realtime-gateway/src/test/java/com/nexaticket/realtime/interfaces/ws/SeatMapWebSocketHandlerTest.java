// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.realtime.interfaces.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaticket.realtime.application.AvailabilityUpdate;
import com.nexaticket.realtime.application.SessionRegistry;
import com.nexaticket.realtime.application.UpdateCoalescer;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * {@link SeatMapWebSocketHandler}: vòng đời kết nối và đẩy thông báo gộp.
 *
 * <p>WebSocketSession là mock; thứ kiểm là quyết định của handler (nhận hay đóng, gỡ khỏi sổ ở mọi
 * đường, đóng kết nối chậm) chứ không phải giao thức WebSocket.
 */
class SeatMapWebSocketHandlerTest {

    private static final UUID SESSION = UUID.fromString("11111111-1111-4111-a111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-4222-a222-222222222222");

    private final SessionRegistry<WebSocketSession> registry = new SessionRegistry<>(2);
    private final UpdateCoalescer coalescer = new UpdateCoalescer();
    private final ObjectMapper json = new ObjectMapper();
    private final SeatMapWebSocketHandler handler = new SeatMapWebSocketHandler(registry, coalescer, json);

    private static WebSocketSession socket(String uri) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getUri()).thenReturn(uri == null ? null : URI.create(uri));
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    @Nested
    @DisplayName("Kết nối")
    class Connect {

        @Test
        @DisplayName("đường dẫn /ws/sessions/{uuid}: đăng ký vào đúng suất, không đóng")
        void dang_ky_dung_suat() throws Exception {
            WebSocketSession session = socket("ws://host/ws/sessions/" + SESSION);

            handler.afterConnectionEstablished(session);

            assertThat(registry.connectionCount(SESSION)).isEqualTo(1);
            verify(session, never()).close(any(CloseStatus.class));
        }

        @Test
        @DisplayName("kết nối ẩn danh (không token) vẫn được nhận — sơ đồ chỗ là dữ liệu công khai")
        void an_danh_van_duoc_nhan() {
            handler.afterConnectionEstablished(socket("ws://host/ws/sessions/" + SESSION));

            assertThat(registry.totalConnections()).isEqualTo(1);
        }

        @Test
        @DisplayName("id không phải UUID hoặc không có URI: đóng POLICY_VIOLATION, không đăng ký")
        void id_sai_bi_dong() throws Exception {
            WebSocketSession bad = socket("ws://host/ws/sessions/khong-phai-uuid");
            WebSocketSession noUri = socket(null);

            handler.afterConnectionEstablished(bad);
            handler.afterConnectionEstablished(noUri);

            verify(bad).close(CloseStatus.POLICY_VIOLATION);
            verify(noUri).close(CloseStatus.POLICY_VIOLATION);
            assertThat(registry.totalConnections()).isZero();
        }

        @Test
        @DisplayName("kết nối mở thêm suất vượt trần: bị từ chối (trần 2 trong test này)")
        void vuot_tran() {
            // Một socket thật chỉ có một URI nên chỉ theo dõi được một suất; trần được kiểm ở registry.
            WebSocketSession session = socket("ws://host/ws/sessions/" + SESSION);
            registry.subscribe(session, OTHER);
            registry.subscribe(session, UUID.randomUUID());

            handler.afterConnectionEstablished(session);

            try {
                verify(session).close(CloseStatus.POLICY_VIOLATION);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Nested
    @DisplayName("Đóng và lỗi")
    class Close {

        @Test
        @DisplayName("đóng bình thường: gỡ khỏi sổ")
        void dong_binh_thuong() {
            WebSocketSession session = socket("ws://host/ws/sessions/" + SESSION);
            handler.afterConnectionEstablished(session);

            handler.afterConnectionClosed(session, CloseStatus.NORMAL);

            assertThat(registry.totalConnections()).isZero();
            assertThat(registry.connectionCount(SESSION)).isZero();
        }

        @Test
        @DisplayName("lỗi truyền tải: cũng gỡ khỏi sổ — không rò rỉ bộ nhớ khi client mất mạng")
        void loi_truyen_tai() {
            WebSocketSession session = socket("ws://host/ws/sessions/" + SESSION);
            handler.afterConnectionEstablished(session);

            handler.handleTransportError(session, new IOException("connection reset"));

            assertThat(registry.totalConnections()).isZero();
        }
    }

    @Nested
    @DisplayName("flush")
    class Flush {

        @Test
        @DisplayName("mỗi suất đúng một message JSON {type, eventSessionId, version} tới đúng người theo dõi")
        void day_thong_bao() throws Exception {
            WebSocketSession watcher = socket("ws://host/ws/sessions/" + SESSION);
            WebSocketSession bystander = socket("ws://host/ws/sessions/" + OTHER);
            handler.afterConnectionEstablished(watcher);
            handler.afterConnectionEstablished(bystander);
            coalescer.accept(new AvailabilityUpdate(SESSION, 5));
            coalescer.accept(new AvailabilityUpdate(SESSION, 9));
            coalescer.accept(new AvailabilityUpdate(SESSION, 7));

            handler.flush();

            ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
            verify(watcher, times(1)).sendMessage(sent.capture());
            JsonNode payload = json.readTree(sent.getValue().getPayload());
            assertThat(payload.get("type").asText()).isEqualTo("availability");
            assertThat(payload.get("eventSessionId").asText()).isEqualTo(SESSION.toString());
            assertThat(payload.get("version").asLong()).isEqualTo(9);
            verify(bystander, never()).sendMessage(any());
        }

        @Test
        @DisplayName("flush xong thì bộ gộp rỗng: lượt sau không gửi lại")
        void khong_gui_lai() throws Exception {
            WebSocketSession watcher = socket("ws://host/ws/sessions/" + SESSION);
            handler.afterConnectionEstablished(watcher);
            coalescer.accept(new AvailabilityUpdate(SESSION, 1));

            handler.flush();
            handler.flush();

            verify(watcher, times(1)).sendMessage(any());
        }

        @Test
        @DisplayName("kết nối đã đóng: bị bỏ qua, không gửi")
        void ket_noi_da_dong() throws Exception {
            WebSocketSession closed = socket("ws://host/ws/sessions/" + SESSION);
            handler.afterConnectionEstablished(closed);
            when(closed.isOpen()).thenReturn(false);
            coalescer.accept(new AvailabilityUpdate(SESSION, 1));

            handler.flush();

            verify(closed, never()).sendMessage(any());
        }

        @Test
        @DisplayName("client chậm làm sendMessage ném: đóng SESSION_NOT_RELIABLE, gỡ khỏi sổ, kết nối khác vẫn nhận")
        void client_cham_bi_dong() throws Exception {
            WebSocketSession slow = socket("ws://host/ws/sessions/" + SESSION);
            WebSocketSession healthy = socket("ws://host/ws/sessions/" + SESSION);
            handler.afterConnectionEstablished(slow);
            handler.afterConnectionEstablished(healthy);
            doThrow(new IllegalStateException("buffer full")).when(slow).sendMessage(any());
            coalescer.accept(new AvailabilityUpdate(SESSION, 3));

            handler.flush();

            verify(slow).close(CloseStatus.SESSION_NOT_RELIABLE);
            verify(healthy).sendMessage(any());
            assertThat(registry.connectionCount(SESSION)).isEqualTo(1);
        }

        @Test
        @DisplayName("IOException khi gửi cũng bị xử lý như client chậm")
        void io_exception() throws Exception {
            WebSocketSession broken = socket("ws://host/ws/sessions/" + SESSION);
            handler.afterConnectionEstablished(broken);
            doThrow(new IOException("broken pipe")).when(broken).sendMessage(any());
            coalescer.accept(new AvailabilityUpdate(SESSION, 3));

            handler.flush();

            verify(broken).close(CloseStatus.SESSION_NOT_RELIABLE);
            assertThat(registry.totalConnections()).isZero();
        }

        @Test
        @DisplayName("không có gì để gửi: flush không làm gì")
        void khong_co_gi() throws Exception {
            WebSocketSession watcher = socket("ws://host/ws/sessions/" + SESSION);
            handler.afterConnectionEstablished(watcher);

            handler.flush();

            verify(watcher, never()).sendMessage(any());
        }
    }
}
