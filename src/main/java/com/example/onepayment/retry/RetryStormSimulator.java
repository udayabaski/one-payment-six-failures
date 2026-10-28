package com.example.onepayment.retry;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Failure #3: models thousands of clients retrying against a service that is down for a while
 * and then has limited capacity. Runs in virtual time, so 10 minutes simulate in milliseconds.
 */
public final class RetryStormSimulator {

	public enum Strategy {

		IMMEDIATE("retry every second, ignore Retry-After"),

		RETRY_AFTER_ONLY("wait exactly Retry-After, nothing else"),

		RETRY_AFTER_WITH_BACKOFF_AND_JITTER("Retry-After as a floor + exponential backoff + jitter");

		private final String description;

		Strategy(String description) {
			this.description = description;
		}

		public String description() {
			return this.description;
		}
	}

	/**
	 * @param outage the dependency is down from t=0 until this moment
	 * @param capacityPerSecond requests the service can accept per second once it is back
	 */
	public record Scenario(int clients, Duration outage, int capacityPerSecond, Duration retryAfter, int maxAttempts,
			Duration deadline, long seed) {

		public static Scenario defaults() {
			return new Scenario(10_000, Duration.ofSeconds(30), 1_000, Duration.ofSeconds(30), 10,
					Duration.ofMinutes(10), 42);
		}
	}

	/**
	 * @param peakDuringOutage busiest second while the dependency was down
	 * @param peakAfterRecovery busiest second once it came back: the retry storm, if any
	 */
	public record Result(Strategy strategy, Scenario scenario, int[] attemptsPerSecond, int peakDuringOutage,
			int peakAfterRecovery, long totalAttempts, int succeeded, int gaveUp, int lastSuccessSecond) {
	}

	private static final RetryPolicy JITTER_POLICY = new RetryPolicy(Integer.MAX_VALUE, Duration.ofSeconds(1),
			Duration.ofSeconds(60), Duration.ofMinutes(10));

	private RetryStormSimulator() {
	}

	public static Result run(Strategy strategy, Scenario scenario) {
		Random random = new Random(scenario.seed());
		long outageMs = scenario.outage().toMillis();
		long retryAfterMs = scenario.retryAfter().toMillis();
		long deadlineMs = scenario.deadline().toMillis();
		int seconds = (int) (deadlineMs / 1000) + 2;
		int[] attempts = new int[seconds];
		int[] admitted = new int[seconds];
		long[] firstAttemptAt = new long[scenario.clients()];

		// {time ms, client, attempt number}
		PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
		for (int client = 0; client < scenario.clients(); client++) {
			// Every in-flight payment fails within the first second of the outage.
			firstAttemptAt[client] = random.nextLong(1000);
			queue.add(new long[] { firstAttemptAt[client], client, 1 });
		}

		long total = 0;
		int succeeded = 0;
		int gaveUp = 0;
		int lastSuccessSecond = -1;
		while (!queue.isEmpty()) {
			long[] event = queue.poll();
			long now = event[0];
			int client = (int) event[1];
			int attempt = (int) event[2];
			int second = (int) (now / 1000);
			attempts[second]++;
			total++;
			if (now >= outageMs && admitted[second] < scenario.capacityPerSecond()) {
				admitted[second]++;
				succeeded++;
				lastSuccessSecond = Math.max(lastSuccessSecond, second);
				continue;
			}
			// Rejected with 503 + Retry-After.
			long next = now + delay(strategy, attempt, retryAfterMs, random);
			if (attempt >= scenario.maxAttempts() || next - firstAttemptAt[client] > deadlineMs) {
				gaveUp++;
			}
			else {
				queue.add(new long[] { next, client, attempt + 1 });
			}
		}
		int recoverySecond = (int) (outageMs / 1000);
		int peakDuringOutage = Arrays.stream(attempts, 0, recoverySecond).max().orElse(0);
		int peakAfterRecovery = Arrays.stream(attempts, recoverySecond, attempts.length).max().orElse(0);
		return new Result(strategy, scenario, attempts, peakDuringOutage, peakAfterRecovery, total, succeeded, gaveUp,
				lastSuccessSecond);
	}

	private static long delay(Strategy strategy, int attempt, long retryAfterMs, Random random) {
		return switch (strategy) {
			case IMMEDIATE -> 1_000;
			case RETRY_AFTER_ONLY -> retryAfterMs;
			case RETRY_AFTER_WITH_BACKOFF_AND_JITTER ->
				JITTER_POLICY.delayBeforeRetry(attempt, Optional.of(Duration.ofMillis(retryAfterMs)), random).toMillis();
		};
	}

	/** A text histogram, 10-second buckets. */
	public static String render(Result result) {
		Scenario s = result.scenario();
		StringBuilder out = new StringBuilder();
		out.append(String.format("Strategy: %s (%s)%n", result.strategy(), result.strategy().description()));
		out.append(String.format("clients=%,d  outage=%ds  capacity=%,d req/s  Retry-After=%ds  maxAttempts=%d%n",
				s.clients(), s.outage().toSeconds(), s.capacityPerSecond(), s.retryAfter().toSeconds(),
				s.maxAttempts()));
		out.append(String.format("peak while down=%,d req/s  peak after recovery=%,d req/s%n", result.peakDuringOutage(),
				result.peakAfterRecovery()));
		out.append(String.format("attempts=%,d  succeeded=%,d  gave up=%,d  last success: %s%n", result.totalAttempts(),
				result.succeeded(), result.gaveUp(),
				(result.lastSuccessSecond() < 0) ? "never" : "t=" + result.lastSuccessSecond() + "s"));
		int[] perSecond = result.attemptsPerSecond();
		int lastActive = perSecond.length - 1;
		while (lastActive > 0 && perSecond[lastActive] == 0) {
			lastActive--;
		}
		int bucketSize = 10;
		int buckets = lastActive / bucketSize + 1;
		int[] bucketTotals = new int[buckets];
		for (int second = 0; second <= lastActive; second++) {
			bucketTotals[second / bucketSize] += perSecond[second];
		}
		int max = Math.max(1, Arrays.stream(bucketTotals).max().orElse(1));
		for (int b = 0; b < buckets; b++) {
			int width = (int) Math.round(50.0 * bucketTotals[b] / max);
			out.append(String.format("  t=%3d-%3ds %,8d %s%n", b * bucketSize, b * bucketSize + bucketSize - 1,
					bucketTotals[b], "#".repeat(width)));
		}
		return out.toString();
	}
}
