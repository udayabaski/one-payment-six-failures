package com.example.onepayment.tracing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

import com.example.onepayment.simulation.Resettable;
import reactor.core.publisher.Mono;

import org.springframework.stereotype.Component;

/** Plays the role of the tracing backend (Jaeger, Tempo, Zipkin, ...). */
@Component
public class InMemorySpanExporter implements Resettable {

	private final Queue<SpanData> spans = new ConcurrentLinkedQueue<>();

	void export(SpanData span) {
		this.spans.add(span);
	}

	public List<SpanData> trace(String traceId) {
		return this.spans.stream()
			.filter(span -> span.traceId().equals(traceId))
			.sorted(Comparator.comparing(SpanData::start))
			.toList();
	}

	public List<SpanData> all() {
		return List.copyOf(this.spans);
	}

	/** Renders a trace as an indented tree, like the waterfall view of a tracing UI. */
	public String render(String traceId) {
		List<SpanData> trace = trace(traceId);
		if (trace.isEmpty()) {
			return "No spans recorded for trace " + traceId + System.lineSeparator();
		}
		Set<String> ids = trace.stream().map(SpanData::spanId).collect(Collectors.toSet());
		Map<String, List<SpanData>> children = trace.stream()
			.filter(span -> span.parentSpanId() != null && ids.contains(span.parentSpanId()))
			.collect(Collectors.groupingBy(SpanData::parentSpanId));
		StringBuilder out = new StringBuilder("trace " + traceId + System.lineSeparator());
		trace.stream()
			.filter(span -> span.parentSpanId() == null || !ids.contains(span.parentSpanId()))
			.forEach(root -> render(root, "", "", children, out));
		return out.toString();
	}

	private void render(SpanData span, String prefix, String childPrefix, Map<String, List<SpanData>> children,
			StringBuilder out) {
		String label = prefix + "[" + span.service() + "] " + span.name()
				+ ("true".equals(span.attributes().get("async")) ? "  (async)" : "");
		out.append(String.format("%-78s %,7d ms%n", label, span.duration().toMillis()));
		List<SpanData> kids = new ArrayList<>(children.getOrDefault(span.spanId(), List.of()));
		kids.sort(Comparator.comparing(SpanData::start));
		for (int i = 0; i < kids.size(); i++) {
			boolean last = i == kids.size() - 1;
			render(kids.get(i), childPrefix + " +-- ", childPrefix + (last ? "     " : " |   "), children, out);
		}
	}

	@Override
	public Mono<Void> reset() {
		return Mono.fromRunnable(this.spans::clear);
	}
}
