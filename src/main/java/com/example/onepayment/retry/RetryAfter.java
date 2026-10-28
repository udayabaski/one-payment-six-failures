package com.example.onepayment.retry;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/** Parses {@code Retry-After}: either delay-seconds ({@code 30}) or an HTTP-date. */
public final class RetryAfter {

	private RetryAfter() {
	}

	public static Optional<Duration> parse(String value, Clock clock) {
		if (value == null || value.isBlank()) {
			return Optional.empty();
		}
		String trimmed = value.trim();
		if (trimmed.chars().allMatch(Character::isDigit)) {
			return Optional.of(Duration.ofSeconds(Long.parseLong(trimmed)));
		}
		try {
			ZonedDateTime date = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
			Duration delay = Duration.between(clock.instant(), date.toInstant());
			return Optional.of(delay.isNegative() ? Duration.ZERO : delay);
		}
		catch (DateTimeParseException ex) {
			return Optional.empty();
		}
	}
}
