package com.example.onepayment;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.example.onepayment.provider.FakePaymentProvider;
import com.example.onepayment.simulation.SimulationReset;
import org.junit.jupiter.api.BeforeEach;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Boots the whole platform on a random port. Every scenario starts from a clean slate:
 * no charges at the provider, no idempotency records, refund REF-123 back at version 7.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"payments.idempotency.lease=500ms",
		"payments.retry-after.provider-unavailable=1s",
		"payments.provider.timeout=1s",
		"payments.provider.latency=50ms",
		"payments.tracing.notification-latency=200ms" })
public abstract class ScenarioTest {

	protected static final String PAYMENT_FOR_ORDER_123 = """
			{"orderId": "ORDER-123", "amount": 10000, "currency": "USD"}""";

	@LocalServerPort
	protected int port;

	@Autowired
	protected SimulationReset simulation;

	@Autowired
	protected FakePaymentProvider provider;

	protected WebTestClient http;

	protected WebClient web;

	@BeforeEach
	void resetSimulation() {
		this.simulation.resetAll().block();
		String baseUrl = "http://localhost:" + this.port;
		this.http = WebTestClient.bindToServer().baseUrl(baseUrl).responseTimeout(Duration.ofSeconds(30)).build();
		this.web = WebClient.builder().baseUrl(baseUrl).build();
	}

	protected WebTestClient.ResponseSpec postPayment(String path, String token, String idempotencyKey, String body) {
		return this.http.post()
			.uri(path)
			.headers(headers -> {
				headers.setBearerAuth(token);
				if (idempotencyKey != null) {
					headers.set("Idempotency-Key", "\"" + idempotencyKey + "\"");
				}
			})
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(body)
			.exchange();
	}

	/** Fires {@code count} identical requests at the same time and returns every response. */
	protected List<ResponseEntity<Map>> concurrently(int count, String path, String token, String idempotencyKey,
			String body) {
		return Flux.range(0, count)
			.flatMap(i -> this.web.post()
				.uri(path)
				.headers(headers -> {
					headers.setBearerAuth(token);
					headers.set("Idempotency-Key", "\"" + idempotencyKey + "\"");
				})
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body)
				.exchangeToMono(response -> response.toEntity(Map.class)), count)
			.collectList()
			.block(Duration.ofSeconds(30));
	}

	protected static void narrate(String format, Object... args) {
		System.out.printf("    >> " + format + "%n", args);
	}

	protected static void pause(Duration duration) {
		Mono.delay(duration).block();
	}
}
