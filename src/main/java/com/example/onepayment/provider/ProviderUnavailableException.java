package com.example.onepayment.provider;

/** The provider told us it did not attempt the charge. Safe to try again later. */
public class ProviderUnavailableException extends RuntimeException {

	public ProviderUnavailableException() {
		super("Payment provider is unavailable");
	}
}
