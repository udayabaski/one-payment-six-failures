package com.example.onepayment;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Failure #4: the rate limiter works, the attacker walks around it")
class Failure4RateLimitTest extends ScenarioTest {

	private static final String WRONG_PASSWORD = """
			{"username": "alice", "password": "guess"}""";

	@Test
	@DisplayName("NAIVE: rotating X-Forwarded-For gives every attempt a fresh bucket")
	void naiveLimiterIsBypassedWithSpoofedHeader() {
		List<Integer> statuses = bruteForce("/naive/f4/login", 20);

		assertThat(statuses).doesNotContain(429).containsOnly(401);
		narrate("20 password guesses, %d were rate limited", statuses.stream().filter(s -> s == 429).count());
	}

	@Test
	@DisplayName("FIXED: headers from an untrusted peer are ignored; the limit holds")
	void fixedLimiterIgnoresSpoofedHeader() {
		List<Integer> statuses = bruteForce("/api/login", 20);

		assertThat(statuses.subList(0, 5)).containsOnly(401);
		assertThat(statuses.subList(5, 20)).containsOnly(429);
		narrate("20 password guesses, %d were rate limited", statuses.stream().filter(s -> s == 429).count());
	}

	@Test
	@DisplayName("RateLimit headers describe the policy; Retry-After comes with the 429")
	void rateLimitHeadersDescribeThePolicy() {
		bruteForce("/api/login", 5);
		this.http.post()
			.uri("/api/login")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(WRONG_PASSWORD)
			.exchange()
			.expectStatus().isEqualTo(429)
			.expectHeader().valueEquals("RateLimit-Policy", "\"login\";q=5;w=60")
			.expectHeader().value("RateLimit", value -> assertThat(value).startsWith("\"login\";r=0;t="))
			.expectHeader().exists("Retry-After");
	}

	private List<Integer> bruteForce(String path, int attempts) {
		List<Integer> statuses = new ArrayList<>();
		for (int i = 0; i < attempts; i++) {
			EntityExchangeResult<byte[]> result = this.http.post()
				.uri(path)
				.header("X-Forwarded-For", "192.0.2." + (10 + i))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(WRONG_PASSWORD)
				.exchange()
				.expectBody()
				.returnResult();
			statuses.add(result.getStatus().value());
		}
		return statuses;
	}
}
