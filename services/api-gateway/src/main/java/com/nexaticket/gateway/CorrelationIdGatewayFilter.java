// SPDX-License-Identifier: UNLICENSED
package com.nexaticket.gateway;

import com.nexaticket.kernel.id.CorrelationId;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Sinh correlation id ở biên và truyền xuống mọi service phía sau. */
@Component
public class CorrelationIdGatewayFilter implements WebFilter, Ordered {

    private static final String HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String correlationId = (incoming == null || incoming.isBlank() || incoming.length() > 64)
                ? CorrelationId.generate().value()
                : incoming;

        ServerHttpRequest mutated =
                exchange.getRequest().mutate().header(HEADER, correlationId).build();
        exchange.getResponse().getHeaders().set(HEADER, correlationId);
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
