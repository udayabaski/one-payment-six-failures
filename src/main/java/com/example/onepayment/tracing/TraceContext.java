package com.example.onepayment.tracing;

import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The part of W3C Trace Context carried by the {@code traceparent} header:
 * {@code version-traceId-parentSpanId-flags}.
 */
public record TraceContext(String traceId, String spanId, boolean sampled) {

	private static final Pattern TRACEPARENT = Pattern
		.compile("^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

	private static final String INVALID_TRACE_ID = "0".repeat(32);

	private static final String INVALID_SPAN_ID = "0".repeat(16);

	/**
	 * Parses an incoming header. Anything malformed is rejected rather than "repaired":
	 * incoming trace context is untrusted input.
	 */
	public static Optional<TraceContext> parse(String header) {
		if (header == null) {
			return Optional.empty();
		}
		Matcher m = TRACEPARENT.matcher(header.trim());
		if (!m.matches() || m.group(1).equals("ff")) {
			return Optional.empty();
		}
		String traceId = m.group(2);
		String spanId = m.group(3);
		if (traceId.equals(INVALID_TRACE_ID) || spanId.equals(INVALID_SPAN_ID)) {
			return Optional.empty();
		}
		boolean sampled = (Integer.parseInt(m.group(4), 16) & 0x01) == 1;
		return Optional.of(new TraceContext(traceId, spanId, sampled));
	}

	public static TraceContext newRoot() {
		return new TraceContext(randomHex(16), randomHex(8), true);
	}

	public TraceContext newChild() {
		return new TraceContext(this.traceId, randomHex(8), this.sampled);
	}

	public String toTraceparent() {
		return "00-" + this.traceId + "-" + this.spanId + "-" + (this.sampled ? "01" : "00");
	}

	private static String randomHex(int bytes) {
		byte[] buffer = new byte[bytes];
		do {
			ThreadLocalRandom.current().nextBytes(buffer);
		}
		while (isAllZero(buffer));
		return HexFormat.of().formatHex(buffer);
	}

	private static boolean isAllZero(byte[] buffer) {
		for (byte b : buffer) {
			if (b != 0) {
				return false;
			}
		}
		return true;
	}
}
