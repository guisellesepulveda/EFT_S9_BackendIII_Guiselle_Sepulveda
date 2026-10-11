package cl.bancoxyz.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import reactor.core.publisher.Mono;

/**
 * Propagación de traceId: Micrometer Tracing inyecta el contexto (traceparent) en la llamada a cada servicio; además se
 * devuelve el id al cliente en X-Trace-Id para que pueda citarlo al reportar un problema y buscarlo en Zipkin/logs.
 */
@Component
class TraceIdFilter implements GlobalFilter, Ordered {

    static final String HEADER = "X-Trace-Id";

    private final Tracer tracer;

    TraceIdFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Span span = tracer.currentSpan();
        if (span != null) {
            String traceId = span.context().traceId();
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().set(HEADER, traceId);
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
