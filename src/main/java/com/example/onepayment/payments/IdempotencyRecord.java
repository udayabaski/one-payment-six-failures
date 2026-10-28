package com.example.onepayment.payments;

import java.time.OffsetDateTime;

public record IdempotencyRecord(String tenantId, String idempotencyKey, String requestFingerprint, String paymentId,
		String providerRequestId, Status status, Integer responseStatus, String responseBody,
		OffsetDateTime lockedUntil, OffsetDateTime createdAt, OffsetDateTime expiresAt) {

	public enum Status {
		PROCESSING, COMPLETED
	}
}
