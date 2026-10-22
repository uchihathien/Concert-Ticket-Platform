// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.aichatbox.infrastructure.llm;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Máy chủ HTTP thật trên cổng ngẫu nhiên, trả một thân JSON cố định và ghi lại thân request.
 *
 * <p>Không dùng {@code MockRestServiceServer}: adapter tự đặt {@code requestFactory} của nó (pool kết
 * nối, hạn đọc), đè mất factory giả. Đi qua socket thật thì kiểm được đúng đường adapter dùng.
 */
final class StubHttpServer implements AutoCloseable {

    private final HttpServer server;
    final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private volatile String responseBody = "{}";

    StubHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    StubHttpServer respond(String json) {
        this.responseBody = json;
        return this;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
