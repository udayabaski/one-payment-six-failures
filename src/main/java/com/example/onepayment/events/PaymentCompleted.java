package com.example.onepayment.events;

public record PaymentCompleted(String paymentId, String orderId, long amount, String currency) {
}
