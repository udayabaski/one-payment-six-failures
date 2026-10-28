package com.example.onepayment.retry;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Failure #3: which failures are safe to replay")
class RetryDecisionTest {

	@Test
	@DisplayName("Without an idempotency key, a payment POST is never replayed")
	void noKeyNoRetry() {
		assertThat(ResilientPaymentClient.isRetryable(status(503), false)).isFalse();
		assertThat(ResilientPaymentClient.isRetryable(new TimeoutException(), false)).isFalse();
	}

	@Test
	@DisplayName("With a key: retry overload, conflicts and ambiguous failures, but not client errors")
	void withKey() {
		assertThat(ResilientPaymentClient.isRetryable(status(503), true)).isTrue();
		assertThat(ResilientPaymentClient.isRetryable(status(429), true)).isTrue();
		assertThat(ResilientPaymentClient.isRetryable(status(409), true)).isTrue();
		assertThat(ResilientPaymentClient.isRetryable(new TimeoutException(), true)).isTrue();
		assertThat(ResilientPaymentClient.isRetryable(status(400), true)).isFalse();
		assertThat(ResilientPaymentClient.isRetryable(status(403), true)).isFalse();
		assertThat(ResilientPaymentClient.isRetryable(status(422), true)).isFalse();
	}

	@Test
	@DisplayName("Retry-After accepts delay-seconds and HTTP-date")
	void parsesRetryAfter() {
		Clock clock = Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneOffset.UTC);
		assertThat(RetryAfter.parse("30", clock)).contains(Duration.ofSeconds(30));
		assertThat(RetryAfter.parse("Wed, 28 Oct 2026 12:01:00 GMT", clock)).contains(Duration.ofSeconds(60));
		assertThat(RetryAfter.parse("soon", clock)).isEmpty();
	}

	@Test
	@DisplayName("The policy never waits less than Retry-After, and adds jitter on top")
	void policyRespectsRetryAfterAsFloor() {
		var policy = new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofMinutes(5));
		var random = new Random(1);
		for (int i = 0; i < 1_000; i++) {
			Duration delay = policy.delayBeforeRetry(1, Optional.of(Duration.ofSeconds(30)), random);
			assertThat(delay).isBetween(Duration.ofSeconds(30), Duration.ofSeconds(60));
		}
		assertThat(policy.delayBeforeRetry(4, Optional.empty(), random)).isBetween(Duration.ofSeconds(8),
				Duration.ofSeconds(16));
	}

	private static WebClientResponseException status(int status) {
		return WebClientResponseException.create(status, "status " + status, new HttpHeaders(), new byte[0],
				StandardCharsets.UTF_8);
	}
}
