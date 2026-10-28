package com.example.onepayment.provider;

/**
 * @param reference our payment id, stored by the provider so we can look the charge up later
 */
public record ProviderChargeRequest(String reference, long amount, String currency) {
}
