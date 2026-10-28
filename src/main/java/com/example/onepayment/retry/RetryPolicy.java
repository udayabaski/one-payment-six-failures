package com.example.onepayment.retry;

import java.time.Duration;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Everything {@code Retry-After} does not give you: backoff, jitter, a retry budget and a deadline.
 *
 * @param maxAttempts total attempts including the first one (the retry budget)
 * @param baseDelay first backoff step; doubles after every failure
 * @param maxDelay cap for the exponential backoff
 * @param deadline give up once the whole operation has taken this long
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay, Duration deadline) {

	/**
	 * Waits at least what the server asked for (or the backoff step, whichever is longer),
	 * plus a random extra of up to the same amount so clients don't stay in lockstep.
	 * @param retryNumber 1 for the first retry
	 */
	public Duration delayBeforeRetry(int retryNumber, Optional<Duration> retryAfter, RandomGenerator random) {
		long backoff = Math.min(this.maxDelay.toMillis(), this.baseDelay.toMillis() << Math.min(retryNumber - 1, 30));
		long floor = Math.max(backoff, retryAfter.map(Duration::toMillis).orElse(0L));
		return Duration.ofMillis(floor + random.nextLong(floor + 1));
	}
}
