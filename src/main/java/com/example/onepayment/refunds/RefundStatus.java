package com.example.onepayment.refunds;

public enum RefundStatus {

	PENDING, APPROVED, REJECTED;

	/** Business rule, separate from concurrency control: decisions are final. */
	public boolean canTransitionTo(RefundStatus next) {
		return this == PENDING && next != PENDING;
	}
}
