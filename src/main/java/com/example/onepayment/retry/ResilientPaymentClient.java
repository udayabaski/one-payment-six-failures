package com.example.onepayment.retry;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

import com.example.onepayment.payments.PaymentRequest;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Failure #3: a client that retries payments without amplifying an outage or charging twice.
 * <ul>
 * <li>honours {@code Retry-After} as a minimum wait;</li>
 * <li>adds exponential backoff and jitter on top;</li>
 * <li>stops after a retry budget or deadline;</li>
 * <li>never retries a payment that has no idempotency key.</li>
 * </ul>
 */
public class ResilientPaymentClient {

	private final WebClient webClient;

	private final RetryPolicy policy;

	private final RandomGenerator random;

	private final Clock clock;

	public ResilientPaymentClient(WebClient webClient, RetryPolicy policy, RandomGenerator random, Clock clock) {
		this.webClient = webClient;
		this.policy = policy;
		this.random = random;
		this.clock = clock;
	}

	public record Result(int status, String body, int attempts) {
	}

	public Mono<Result> pay(String accessToken, String idempotencyKey, PaymentRequest request) {
		AtomicInteger attempts = new AtomicInteger();
		Mono<Result> attempt = Mono.defer(() -> {
			attempts.incrementAndGet();
			return this.webClient.post()
				.uri("/api/payments")
				.headers(headers -> {
					headers.setBearerAuth(accessToken);
					if (idempotencyKey != null) {
						headers.set("Idempotency-Key", "\"" + idempotencyKey + "\"");
					}
				})
				.bodyValue(request)
				.retrieve()
				.toEntity(String.class)
				.map(entity -> new Result(entity.getStatusCode().value(), entity.getBody(), attempts.get()));
		});
		return attempt.retryWhen(Retry.from(signals -> signals.concatMap(signal -> {
			Throwable failure = signal.failure();
			int retryNumber = (int) signal.totalRetries() + 1;
			if (!isRetryable(failure, idempotencyKey != null) || retryNumber >= this.policy.maxAttempts()) {
				return Mono.error(failure);
			}
			return Mono.delay(this.policy.delayBeforeRetry(retryNumber, retryAfter(failure), this.random));
		})))
			.timeout(this.policy.deadline())
			.onErrorResume(WebClientResponseException.class, error -> Mono
				.just(new Result(error.getStatusCode().value(), error.getResponseBodyAsString(), attempts.get())));
	}

	/**
	 * A payment POST is only replayed when an idempotency key protects it. Then we also retry
	 * ambiguous failures (timeouts, 500s), because the server can recognise the retry.
	 */
	public static boolean isRetryable(Throwable failure, boolean hasIdempotencyKey) {
		if (!hasIdempotencyKey) {
			return false;
		}
		if (failure instanceof WebClientResponseException response) {
			int status = response.getStatusCode().value();
			return status == 409 || status == 429 || status == 500 || status == 502 || status == 503
					|| status == 504;
		}
		return failure instanceof TimeoutException || failure instanceof WebClientRequestException;
	}

	private Optional<Duration> retryAfter(Throwable failure) {
		if (failure instanceof WebClientResponseException response) {
			return RetryAfter.parse(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), this.clock);
		}
		return Optional.empty();
	}
}
