package com.example.onepayment.common;

/**
 * Stands in for the process dying at a precise moment. Nothing after the throw runs,
 * so no cleanup code gets a chance to "fix" the state, exactly like a real crash.
 */
public class SimulatedCrashException extends RuntimeException {

	public SimulatedCrashException(String where) {
		super("Simulated crash " + where);
	}
}
