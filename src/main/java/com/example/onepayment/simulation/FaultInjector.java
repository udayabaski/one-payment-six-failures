package com.example.onepayment.simulation;

import java.util.concurrent.atomic.AtomicInteger;

import com.example.onepayment.common.SimulatedCrashException;
import reactor.core.publisher.Mono;

import org.springframework.stereotype.Component;

/**
 * Arms one-shot crashes at named points in the payment flow (Failure #2).
 */
@Component
public class FaultInjector implements Resettable {

	private final AtomicInteger crashesAfterProviderCharge = new AtomicInteger();

	public void armCrashAfterProviderCharge(int times) {
		this.crashesAfterProviderCharge.set(times);
	}

	/** Called right after the provider confirmed a charge and before we persist it. */
	public void crashIfArmedAfterProviderCharge() {
		if (this.crashesAfterProviderCharge.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
			throw new SimulatedCrashException("after the provider charged the card, before the result was saved");
		}
	}

	@Override
	public Mono<Void> reset() {
		return Mono.fromRunnable(() -> this.crashesAfterProviderCharge.set(0));
	}
}
