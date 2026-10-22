// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.ticketing.infrastructure;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Máy chủ HTTP một-câu-trả-lời của JDK, cho test adapter.
 *
 * <p>Không dùng {@code MockRestServiceServer}: các adapter của ticketing tự đặt
 * {@code requestFactory} riêng (để có timeout ngắn) SAU khi nhận builder, nên request factory giả
 * mà {@code MockRestServiceServer.bindTo(builder)} cắm vào bị ghi đè và không request nào bị chặn
 * lại. Cùng lý do với {@code OrderingFailureKindTest}: một server thật trả đúng một con số là cách
 * kiểm trung thực nhất cách adapter phân loại mã HTTP.
 */
public final class StubHttpServer implements AutoCloseable {

    /** Một request đã nhận, để kiểm adapter gửi gì đi. */
    public record Received(String method, String path, String body) {}

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";

    public StubHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(
                    exchange.getRequestMethod(), exchange.getRequestURI().getPath(), requestBody));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (bytes.length == 0) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    public StubHttpServer respond(int status, String body) {
        this.status = status;
        this.body = body;
        return this;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
