package com.example.onepayment.tracing;

import java.util.Optional;

import com.example.onepayment.config.PaymentsProperties;
import reactor.core.publisher.Mono;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

/**
 * Starts a SERVER span for every request, continuing the caller's trace when it sent a valid
 * {@code traceparent}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TracingWebFilter implements WebFilter {

	private static final int MAX_TRACESTATE_LENGTH = 512;

	private final Tracer tracer;

	private final PaymentsProperties properties;

	TracingWebFilter(Tracer tracer, PaymentsProperties properties) {
		this.tracer = tracer;
		this.properties = properties;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		String path = exchange.getRequest().getPath().value();
		if (path.startsWith("/simulation")) {
			return chain.filter(exchange);
		}
		HttpHeaders headers = exchange.getRequest().getHeaders();
		String incoming = headers.getFirst("traceparent");
		Optional<TraceContext> parent = TraceContext.parse(incoming);
		String service = path.startsWith("/provider") ? "payment-provider" : "payments-api";
		Span span = this.tracer.startSpan(service, exchange.getRequest().getMethod().name() + " " + path,
				SpanKind.SERVER, parent.orElse(null));
		if (incoming != null && parent.isEmpty()) {
			span.setAttribute("trace.incoming_rejected", "malformed traceparent, started a new trace");
		}

		// tracestate is only meaningful together with a valid traceparent.
		String tracestate = parent.map(p -> headers.getFirst("tracestate"))
			.filter(state -> state.length() <= MAX_TRACESTATE_LENGTH)
			.orElse(null);
		Baggage.Filtered baggage = Baggage.parse(headers.getFirst("baggage"))
			.filter(this.properties.tracing().baggageAllowlist());
		if (!baggage.droppedKeys().isEmpty()) {
			span.setAttribute("baggage.dropped_keys", String.join(",", baggage.droppedKeys()));
		}

		// Convenience for the demo scripts so you can look the trace up afterwards.
		exchange.getResponse().getHeaders().set("X-Trace-Id", span.context().traceId());

		return chain.filter(exchange)
			.doOnError(span::recordError)
			.doFinally(signal -> span.end())
			.contextWrite(ctx -> Tracer.withIncoming(ctx, span.context(), tracestate, baggage.baggage()));
	}
}
