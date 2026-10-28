package com.example.onepayment.payments;

public record PaymentResponse(String paymentId, String orderId, long amount, String currency, String status,
		String providerChargeId) {
}
