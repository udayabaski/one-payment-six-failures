package com.example.onepayment.provider;

/**
 * We do not know whether the provider charged the card: the call timed out or the
 * connection broke mid-request. Retrying blindly could charge twice.
 */
public class ProviderOutcomeUnknownException extends RuntimeException {

	public ProviderOutcomeUnknownException(Throwable cause) {
		super("Outcome of the provider call is unknown", cause);
	}
}
