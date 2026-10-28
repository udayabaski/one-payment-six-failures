package com.example.onepayment.provider;

import java.time.Instant;

public record ProviderCharge(String chargeId, String reference, long amount, String currency, Instant createdAt) {
}
