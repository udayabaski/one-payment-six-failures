package com.example.onepayment.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The thing that actually enforces the limit. Headers only describe it. */
public class FixedWindowRateLimiter {

	private final int limit;

	private final Duration window;

	private final Clock clock;

	private final Map<String, Window> windows = new ConcurrentHashMap<>();

	public FixedWindowRateLimiter(int limit, Duration window, Clock clock) {
		this.limit = limit;
		this.window = window;
		this.clock = clock;
	}

	public Decision tryAcquire(String key) {
		long now = this.clock.millis();
		Window current = this.windows.compute(key, (k, w) -> (w == null || now >= w.start() + this.window.toMillis())
				? new Window(now, 1) : new Window(w.start(), w.count() + 1));
		long resetMillis = current.start() + this.window.toMillis() - now;
		return new Decision(current.count() <= this.limit, this.limit, Math.max(0, this.limit - current.count()),
				Math.max(1, (resetMillis + 999) / 1000));
	}

	public Duration window() {
		return this.window;
	}

	public void clear() {
		this.windows.clear();
	}

	private record Window(long start, int count) {
	}

	public record Decision(boolean allowed, int limit, int remaining, long resetSeconds) {
	}
}
