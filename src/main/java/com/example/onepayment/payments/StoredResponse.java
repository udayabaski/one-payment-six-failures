package com.example.onepayment.payments;

/** The response we stored for an idempotency key, so a retry gets the same answer. */
public record StoredResponse(int status, String body, boolean replayed) {
}
