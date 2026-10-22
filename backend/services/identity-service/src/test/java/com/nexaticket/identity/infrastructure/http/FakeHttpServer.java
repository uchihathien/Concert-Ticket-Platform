// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.identity.infrastructure.http;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * Máy chủ HTTP giả nằm <b>trong</b> chuỗi interceptor của {@link RestClient}.
 *
 * <p>Vì sao không dùng {@code MockRestServiceServer}: hai adapter tự gọi
 * {@code builder.requestFactory(...)} để đặt timeout, và lời gọi đó ghi đè request factory giả mà
 * {@code MockRestServiceServer.bindTo(builder)} đã cắm vào — test sẽ âm thầm đi ra mạng thật.
 * Interceptor thì không bị ghi đè, và trả phản hồi mà không gọi {@code execution} nghĩa là không
 * có kết nối nào được mở.
 */
final class FakeHttpServer implements ClientHttpRequestInterceptor {

    /** Một lời gọi đã nhận, đủ để khẳng định ánh xạ request. */
    record Call(HttpMethod method, URI uri, HttpHeaders headers, String body) {

        String path() {
            return uri.getRawPath();
        }
    }

    private final List<Call> calls = new ArrayList<>();
    private Function<Call, ClientHttpResponse> responder = call -> respond(HttpStatus.NOT_FOUND, "");

    RestClient.Builder builder() {
        return RestClient.builder().requestInterceptor(this);
    }

    void respondWith(Function<Call, ClientHttpResponse> responder) {
        this.responder = responder;
    }

    List<Call> calls() {
        return calls;
    }

    List<Call> callsTo(HttpMethod method, String pathPrefix) {
        return calls.stream()
                .filter(call -> call.method().equals(method) && call.path().startsWith(pathPrefix))
                .toList();
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(request.getHeaders());
        Call call = new Call(request.getMethod(), request.getURI(), headers, new String(body, StandardCharsets.UTF_8));
        calls.add(call);
        ClientHttpResponse response = responder.apply(call);
        if (response == null) {
            throw new IOException("Kết nối bị từ chối (giả lập)");
        }
        return response;
    }

    static ClientHttpResponse respond(HttpStatus status, String body) {
        MockClientHttpResponse response = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response;
    }

    static ClientHttpResponse created(String location) {
        MockClientHttpResponse response = new MockClientHttpResponse(new byte[0], HttpStatus.CREATED);
        if (location != null) {
            response.getHeaders().setLocation(URI.create(location));
        }
        return response;
    }
}
