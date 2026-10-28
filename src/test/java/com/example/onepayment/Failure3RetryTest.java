package com.example.onepayment;

import java.time.Clock;
import java.time.Duration;
import java.util.Random;

import com.example.onepayment.payments.PaymentRequest;
import com.example.onepayment.retry.ResilientPaymentClient;
import com.example.onepayment.retry.RetryPolicy;
import com.example.onepayment.retry.RetryStormSimulator;
import com.example.onepayment.retry.RetryStormSimulator.Scenario;
import com.example.onepayment.retry.RetryStormSimulator.Strategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Failure #3: ten thousand clients retry together")
class Failure3RetryTest extends ScenarioTest {

	@Test
	@DisplayName("Provider down: 503 + Retry-After, and the key is released so a later retry can proceed")
	void providerOutageReturns503WithRetryAfter() {
		this.provider.configure(Duration.ofMillis(50), true);

		postPayment("/api/payments", "token-user-a", "pay-1", PAYMENT_FOR_ORDER_123)
			.expectStatus().isEqualTo(503)
			.expectHeader().valueEquals("Retry-After", "1");

		this.provider.configure(Duration.ofMillis(50), false);
		postPayment("/api/payments", "token-user-a", "pay-1", PAYMENT_FOR_ORDER_123).expectStatus().isCreated();
		assertThat(this.provider.charges()).hasSize(1);
	}

	@Test
	@DisplayName("A resilient client honours Retry-After, backs off with jitter, and succeeds after recovery")
	void resilientClientRidesOutTheOutage() {
		this.provider.configure(Duration.ofMillis(50), true);
		Mono.delay(Duration.ofMillis(1500))
			.subscribe(tick -> this.provider.configure(Duration.ofMillis(50), false));

		var client = new ResilientPaymentClient(this.web,
				new RetryPolicy(6, Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofSeconds(20)),
				new Random(7), Clock.systemUTC());
		var result = client.pay("token-user-a", "pay-2", new PaymentRequest("ORDER-123", 10_000, "USD"))
			.block(Duration.ofSeconds(30));

		assertThat(result.status()).isEqualTo(201);
		assertThat(result.attempts()).isBetween(2, 6);
		assertThat(this.provider.charges()).hasSize(1);
		narrate("Succeeded after %d attempts, provider charges: %d", result.attempts(), this.provider.charges().size());
	}

	@Test
	@DisplayName("Simulation: Retry-After alone moves the spike; jitter and backoff flatten it")
	void retryStormSimulation() {
		Scenario scenario = Scenario.defaults();
		var immediate = RetryStormSimulator.run(Strategy.IMMEDIATE, scenario);
		var retryAfterOnly = RetryStormSimulator.run(Strategy.RETRY_AFTER_ONLY, scenario);
		var withJitter = RetryStormSimulator.run(Strategy.RETRY_AFTER_WITH_BACKOFF_AND_JITTER, scenario);
		System.out.println(RetryStormSimulator.render(immediate));
		System.out.println(RetryStormSimulator.render(retryAfterOnly));
		System.out.println(RetryStormSimulator.render(withJitter));

		// Hammering a dead dependency burns the whole retry budget before it recovers.
		assertThat(immediate.succeeded()).isZero();
		// Everyone obeys Retry-After... and comes back in the same second.
		assertThat(retryAfterOnly.peakAfterRecovery()).isEqualTo(scenario.clients());
		assertThat(retryAfterOnly.gaveUp()).isPositive();
		// Spread out, the same clients fit under capacity and everybody gets through.
		assertThat(withJitter.peakAfterRecovery()).isLessThan(scenario.capacityPerSecond());
		assertThat(withJitter.succeeded()).isEqualTo(scenario.clients());
	}
}
