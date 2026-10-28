package com.example.onepayment.orders;

public record Order(String orderId, String ownerId, long amount, String currency) {
}
