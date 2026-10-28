package com.example.onepayment.payments;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** @param amount in minor units: 10000 means $100.00 */
public record PaymentRequest(@NotBlank String orderId, @Positive long amount, @NotBlank @Size(min = 3, max = 3) String currency) {
}
