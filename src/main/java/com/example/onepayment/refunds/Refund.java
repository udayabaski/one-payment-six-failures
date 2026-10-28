package com.example.onepayment.refunds;

public record Refund(String id, String paymentId, long amount, String currency, RefundStatus status, long version,
		String updatedBy) {
}
