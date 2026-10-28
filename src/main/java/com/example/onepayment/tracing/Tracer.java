package com.example.onepayment.tracing;

import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import org.springframework.stereotype.Component;

/**
 * A deliberately tiny tracer so every step of Failure #6 is visible in plain code.
 * In production you would use OpenTelemetry or Micrometer Tracing; the ideas are identical:
 * the current span lives in the Reactor {@link Context}, children are created from it,
 * and it is injected into outgoing headers.
 */
@Component
public class Tracer {

	private static final String CURRENT_SPAN = Tracer.class.getName() + ".currentSpan";

	private static final String TRACESTATE = Tracer.class.getName() + ".tracestate";

	private static final String BAGGAGE = Tracer.class.getName() + ".baggage";

	private final InMemorySpanExporter exporter;

	public Tracer(InMemorySpanExporter exporter) {
		this.exporter = exporter;
	}

	public Span startSpan(String service, String name, SpanKind kind, TraceContext parent) {
		TraceContext context = (parent != null) ? parent.newChild() : TraceContext.newRoot();
		return new Span(service, name, kind, context, (parent != null) ? parent.spanId() : null, this.exporter);
	}

	/** Runs {@code work} inside a child of the current span. */
	public <T> Mono<T> inSpan(String service, String name, SpanKind kind, Mono<T> work) {
		return Mono.deferContextual(view -> {
			Span span = startSpan(service, name, kind, current(view).orElse(null));
			return work.doOnError(span::recordError)
				.doFinally(signal -> span.end())
				.contextWrite(ctx -> ctx.put(CURRENT_SPAN, span.context()));
		});
	}

	public static Optional<TraceContext> current(ContextView view) {
		return view.getOrEmpty(CURRENT_SPAN);
	}

	public static Context withIncoming(Context context, TraceContext span, String tracestate, Baggage baggage) {
		Context result = context.put(CURRENT_SPAN, span).put(BAGGAGE, baggage);
		return (tracestate != null) ? result.put(TRACESTATE, tracestate) : result;
	}

	/** Writes traceparent / tracestate / baggage for the current span into a carrier. */
	public static void inject(ContextView view, BiConsumer<String, String> carrier) {
		current(view).ifPresent(span -> carrier.accept("traceparent", span.toTraceparent()));
		view.<String>getOrEmpty(TRACESTATE).ifPresent(state -> carrier.accept("tracestate", state));
		view.<Baggage>getOrEmpty(BAGGAGE)
			.filter(baggage -> !baggage.isEmpty())
			.ifPresent(baggage -> carrier.accept("baggage", baggage.toHeader()));
	}

	public static Optional<TraceContext> extract(Map<String, String> carrier) {
		return TraceContext.parse(carrier.get("traceparent"));
	}
}
