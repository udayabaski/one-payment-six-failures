package com.example.onepayment;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.example.onepayment.tracing.InMemorySpanExporter;
import com.example.onepayment.tracing.SpanData;
import com.example.onepayment.tracing.SpanKind;
import com.example.onepayment.tracing.TraceContext;
import com.example.onepayment.tracing.TracingSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("Failure #6: payment latency jumps, nobody knows why")
class Failure6TracingTest extends ScenarioTest {

	// A fresh trace per test, so late async spans from one test can't leak into the next.
	private final String traceId = TraceContext.newRoot().traceId();

	private final String traceparent = "00-" + this.traceId + "-00f067aa0ba902b7-01";

	@Autowired
	InMemorySpanExporter spans;

	@Autowired
	TracingSettings tracingSettings;

	@Test
	@DisplayName("With propagation everywhere, one trace shows the slow provider and the async notification")
	void fullTraceShowsWhereTheTimeWent() {
		this.provider.configure(Duration.ofMillis(800), false);

		pay(traceparent, null).expectStatus().isCreated();
		List<SpanData> trace = awaitNotificationSpan(traceId);
		System.out.println(this.spans.render(traceId));

		SpanData root = span(trace, "POST /api/payments");
		SpanData providerServer = span(trace, "POST /provider/v1/charges");
		SpanData notification = trace.stream().filter(s -> s.service().equals("notification-service")).findFirst().orElseThrow();

		assertThat(trace).extracting(SpanData::service)
			.contains("payments-api", "database", "payment-provider", "notification-service");
		// The provider dominates the request's latency...
		assertThat(providerServer.duration().toMillis()).isGreaterThan(root.duration().toMillis() / 2);
		// ...and the notification finished after the client already had its response.
		assertThat(notification.end()).isAfter(root.end());
	}

	@Test
	@DisplayName("Forget to inject traceparent on the provider call: the provider's span lands in another trace")
	void missingHttpPropagationSplitsTheTrace() {
		this.tracingSettings.configure(false, true);

		pay(traceparent, null).expectStatus().isCreated();
		List<SpanData> trace = awaitNotificationSpan(traceId);

		assertThat(trace).extracting(SpanData::service).doesNotContain("payment-provider");
		assertThat(this.spans.all()).anyMatch(s -> s.service().equals("payment-provider") && !s.traceId().equals(traceId));
		narrate("The provider's own span is missing from this trace:%n%s", this.spans.render(traceId));
	}

	@Test
	@DisplayName("Forget the message headers: the notification becomes an orphan trace")
	void missingMessagingPropagationOrphansTheConsumer() {
		this.tracingSettings.configure(true, false);

		pay(traceparent, null).expectStatus().isCreated();
		Instant requestStart = span(this.spans.trace(traceId), "POST /api/payments").start();
		await().atMost(Duration.ofSeconds(5))
			.until(() -> this.spans.all()
				.stream()
				.anyMatch(s -> s.kind() == SpanKind.CONSUMER && s.start().isAfter(requestStart)));

		assertThat(this.spans.trace(traceId)).extracting(SpanData::service).doesNotContain("notification-service");
	}

	@Test
	@DisplayName("A malformed traceparent is not trusted: a new trace is started")
	void malformedTraceparentStartsNewTrace() {
		String newTraceId = pay("00-NOT-A-VALID-TRACE-01", null)
			.expectStatus().isCreated()
			.returnResult(String.class)
			.getResponseHeaders()
			.getFirst("X-Trace-Id");

		assertThat(newTraceId).isNotBlank().isNotEqualTo(this.traceId);
		assertThat(span(this.spans.trace(newTraceId), "POST /api/payments").attributes()).containsKey("trace.incoming_rejected");
	}

	@Test
	@DisplayName("Baggage is filtered: sensitive keys never travel downstream")
	void sensitiveBaggageIsDropped() {
		pay(traceparent, "client.app=ios-5.2,card.number=4111111111111111,user.email=a@example.com")
			.expectStatus().isCreated();

		assertThat(span(this.spans.trace(traceId), "POST /api/payments").attributes())
			.containsEntry("baggage.dropped_keys", "card.number,user.email");
	}

	private WebTestClient.ResponseSpec pay(String traceparent,
			String baggage) {
		return this.http.post()
			.uri("/api/payments")
			.headers(h -> {
				h.setBearerAuth("token-user-a");
				h.set("Idempotency-Key", "\"trace-demo\"");
				h.set("traceparent", traceparent);
				if (baggage != null) {
					h.set("baggage", baggage);
				}
			})
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(PAYMENT_FOR_ORDER_123)
			.exchange();
	}

	private List<SpanData> awaitNotificationSpan(String traceId) {
		await().atMost(Duration.ofSeconds(5))
			.until(() -> this.spans.trace(traceId).stream().anyMatch(s -> s.service().equals("notification-service")));
		return this.spans.trace(traceId);
	}

	private static SpanData span(List<SpanData> trace, String name) {
		return trace.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
	}
}
