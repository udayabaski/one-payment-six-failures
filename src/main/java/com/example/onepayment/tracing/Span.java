package com.example.onepayment.tracing;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** A span in progress. Ending it hands a {@link SpanData} to the exporter. */
public final class Span {

	private final String service;

	private final String name;

	private final SpanKind kind;

	private final TraceContext context;

	private final String parentSpanId;

	private final Instant start = Instant.now();

	private final Map<String, String> attributes = new ConcurrentHashMap<>();

	private final AtomicBoolean ended = new AtomicBoolean();

	private final InMemorySpanExporter exporter;

	Span(String service, String name, SpanKind kind, TraceContext context, String parentSpanId,
			InMemorySpanExporter exporter) {
		this.service = service;
		this.name = name;
		this.kind = kind;
		this.context = context;
		this.parentSpanId = parentSpanId;
		this.exporter = exporter;
	}

	public TraceContext context() {
		return this.context;
	}

	public Span setAttribute(String key, String value) {
		this.attributes.put(key, value);
		return this;
	}

	public void recordError(Throwable error) {
		setAttribute("error", error.getClass().getSimpleName() + ": " + error.getMessage());
	}

	public void end() {
		if (this.ended.compareAndSet(false, true)) {
			this.exporter.export(new SpanData(this.context.traceId(), this.context.spanId(), this.parentSpanId,
					this.service, this.name, this.kind, this.start, Instant.now(), Map.copyOf(this.attributes)));
		}
	}
}
