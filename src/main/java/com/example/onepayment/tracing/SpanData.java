package com.example.onepayment.tracing;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** A finished span, as a tracing backend would store it. */
public record SpanData(String traceId, String spanId, String parentSpanId, String service, String name,
		SpanKind kind, Instant start, Instant end, Map<String, String> attributes) {

	public Duration duration() {
		return Duration.between(this.start, this.end);
	}
}
