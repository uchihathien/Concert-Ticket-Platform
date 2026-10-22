// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

/**
 * Máy chủ HTTP cục bộ của JDK cho test adapter — không mạng thật, không đồng hồ.
 *
 * <p>Không dùng {@code MockRestServiceServer}: các adapter LLM và nhúng tự đặt {@code requestFactory}
 * SAU khi nhận builder, nên request factory giả cắm bằng {@code bindTo(builder)} bị ghi đè. Một
 * server thật trên cổng tạm là cách kiểm trung thực nhất cách adapter dịch mã HTTP và body.
 */
public final class StubHttpServer implements AutoCloseable {

    /** Một request đã nhận. {@code path} là đường dẫn THÔ (còn nguyên %XX); tên header được hạ chữ thường. */
    public record Received(String method, String path, String query, Map<String, String> headers, String body) {

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /** Một câu trả lời. Body rỗng hoặc null nghĩa là không có body. */
    public record Reply(int status, String body, Map<String, String> headers) {

        public Reply(int status, String body) {
            this(status, body, Map.of());
        }
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile Function<Received, Reply> handler = request -> new Reply(200, "{}");

    public StubHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            Map<String, String> headers = new TreeMap<>();
            exchange.getRequestHeaders()
                    .forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
            Received request = new Received(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(),
                    headers,
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            received.add(request);
            Reply reply = handler.apply(request);
            byte[] bytes = reply.body() == null ? new byte[0] : reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            if (bytes.length == 0) {
                exchange.sendResponseHeaders(reply.status(), -1);
            } else {
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    /** Trả cùng một câu cho mọi request. */
    public StubHttpServer respond(int status, String body) {
        this.handler = request -> new Reply(status, body);
        return this;
    }

    /** Trả câu tuỳ theo request — dùng khi một lời gọi của adapter sinh ra nhiều request. */
    public StubHttpServer respondWith(Function<Received, Reply> handler) {
        this.handler = handler;
        return this;
    }

    /** Giữ request lại cho tới khi {@link #close()} — mô phỏng nhà cung cấp treo, để kiểm quá hạn đọc. */
    public StubHttpServer hang() {
        this.handler = request -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Reply(200, "{}");
        };
        return this;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    /** Request duy nhất đã nhận; sai số lượng là test hỏng. */
    public Received only() {
        if (received.size() != 1) {
            throw new AssertionError("mong đúng 1 request, nhận " + received.size());
        }
        return received.get(0);
    }

    /** Địa chỉ của một cổng vừa đóng — connection refused một cách xác định. */
    public static String deadBaseUrl() {
        try (StubHttpServer closed = new StubHttpServer()) {
            return closed.baseUrl();
        }
    }

    @Override
    public void close() {
        release.countDown();
        server.stop(0);
    }
}
