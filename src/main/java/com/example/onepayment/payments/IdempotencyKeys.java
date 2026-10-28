package com.example.onepayment.payments;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.example.onepayment.common.ApiException;

import org.springframework.http.HttpStatus;

final class IdempotencyKeys {

	private static final int MAX_LENGTH = 255;

	private IdempotencyKeys() {
	}

	/**
	 * The draft defines the header as a Structured Field string, i.e. quoted:
	 * {@code Idempotency-Key: "8e03978e-40d5-43e8-bc93-6894a57f9324"}. Unquoted values are
	 * accepted too, to be forgiving with hand-written curl commands.
	 */
	static String parse(String header) {
		if (header == null || header.isBlank()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Idempotency-Key required",
					"POST /api/payments requires an Idempotency-Key header");
		}
		String key = header.trim();
		if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
			key = key.substring(1, key.length() - 1);
		}
		if (key.isEmpty() || key.length() > MAX_LENGTH) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid Idempotency-Key",
					"Idempotency-Key must be between 1 and " + MAX_LENGTH + " characters");
		}
		return key;
	}

	/** Detects a key being reused for a different operation. */
	static String fingerprint(PaymentRequest request) {
		String canonical = request.orderId() + "|" + request.amount() + "|" + request.currency();
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
