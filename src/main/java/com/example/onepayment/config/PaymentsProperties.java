package com.example.onepayment.config;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("payments")
public record PaymentsProperties(
		Authz authz,
		Idempotency idempotency,
		Provider provider,
		RetryAfter retryAfter,
		RateLimit rateLimit,
		Proxy proxy,
		Refunds refunds,
		Tracing tracing) {

	public record Authz(ForeignOrderResponse foreignOrderResponse) {
	}

	public enum ForeignOrderResponse {
		FORBIDDEN, NOT_FOUND
	}

	public record Idempotency(Duration lease, Duration retention) {
	}

	public record Provider(String baseUrl, Duration timeout, Duration latency) {
	}

	public record RetryAfter(Duration providerUnavailable, Duration inProgress) {
	}

	public record RateLimit(Limit login) {

		public record Limit(int limit, Duration window) {
		}
	}

	public record Proxy(List<String> trustedProxies) {
	}

	public record Refunds(Duration naiveRaceWindow) {
	}

	public record Tracing(Duration notificationLatency, Set<String> baggageAllowlist) {
	}
}
