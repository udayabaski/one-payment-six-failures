package com.example.onepayment.refunds;

import jakarta.validation.constraints.NotNull;

public record RefundStatusUpdate(@NotNull RefundStatus status) {
}
