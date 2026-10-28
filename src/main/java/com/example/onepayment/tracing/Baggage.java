package com.example.onepayment.tracing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * W3C {@code baggage}: application-defined key/value pairs that travel with the request.
 * Whatever goes in here is copied to every downstream service, log pipeline and vendor,
 * so incoming baggage is filtered against an allowlist.
 */
public record Baggage(Map<String, String> entries) {

	private static final int MAX_HEADER_LENGTH = 8192;

	private static final int MAX_VALUE_LENGTH = 256;

	public static final Baggage EMPTY = new Baggage(Map.of());

	public static Baggage parse(String header) {
		if (header == null || header.isBlank() || header.length() > MAX_HEADER_LENGTH) {
			return EMPTY;
		}
		Map<String, String> entries = new LinkedHashMap<>();
		for (String member : header.split(",")) {
			String keyValue = member.split(";", 2)[0];
			int eq = keyValue.indexOf('=');
			if (eq > 0) {
				entries.put(keyValue.substring(0, eq).trim(), keyValue.substring(eq + 1).trim());
			}
		}
		return new Baggage(Collections.unmodifiableMap(entries));
	}

	public Filtered filter(Set<String> allowedKeys) {
		Map<String, String> kept = new LinkedHashMap<>();
		List<String> dropped = new ArrayList<>();
		this.entries.forEach((key, value) -> {
			if (allowedKeys.contains(key) && value.length() <= MAX_VALUE_LENGTH) {
				kept.put(key, value);
			}
			else {
				dropped.add(key);
			}
		});
		return new Filtered(new Baggage(Collections.unmodifiableMap(kept)), List.copyOf(dropped));
	}

	public boolean isEmpty() {
		return this.entries.isEmpty();
	}

	public String toHeader() {
		return this.entries.entrySet()
			.stream()
			.map(e -> e.getKey() + "=" + e.getValue())
			.collect(Collectors.joining(","));
	}

	public record Filtered(Baggage baggage, List<String> droppedKeys) {
	}
}
