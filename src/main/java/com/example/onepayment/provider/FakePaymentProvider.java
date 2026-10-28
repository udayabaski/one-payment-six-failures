package com.example.onepayment.provider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.example.onepayment.config.PaymentsProperties;
import com.example.onepayment.simulation.Resettable;
import reactor.core.publisher.Mono;

import org.springframework.stereotype.Component;

/**
 * The external payment provider: it moves real money, so every charge it records is a charge
 * on the customer's card. Like real providers it
 * <ul>
 * <li>keeps processing a charge even if the caller disconnects or times out;</li>
 * <li>optionally de-duplicates by an {@code Idempotency-Key} the caller supplies;</li>
 * <li>lets the caller look a charge up by its own reference.</li>
 * </ul>
 */
@Component
public class FakePaymentProvider implements Resettable {

	private final Duration defaultLatency;

	private volatile Duration latency;

	private volatile boolean unavailable;

	private final List<ProviderCharge> charges = new CopyOnWriteArrayList<>();

	private final Map<String, CompletableFuture<ProviderCharge>> byIdempotencyKey = new ConcurrentHashMap<>();

	private final AtomicLong sequence = new AtomicLong();

	public FakePaymentProvider(PaymentsProperties properties) {
		this.defaultLatency = properties.provider().latency();
		this.latency = this.defaultLatency;
	}

	public Mono<ProviderCharge> charge(String idempotencyKey, ProviderChargeRequest request) {
		CompletableFuture<ProviderCharge> charge = (idempotencyKey == null) ? process(request)
				: this.byIdempotencyKey.computeIfAbsent(idempotencyKey, key -> process(request));
		// suppressCancel: a caller that gives up does not un-charge the card.
		return Mono.fromFuture(charge, true);
	}

	private CompletableFuture<ProviderCharge> process(ProviderChargeRequest request) {
		var delayed = CompletableFuture.delayedExecutor(this.latency.toMillis(), TimeUnit.MILLISECONDS);
		return CompletableFuture.supplyAsync(() -> {
			var charge = new ProviderCharge("ch_" + this.sequence.incrementAndGet(), request.reference(),
					request.amount(), request.currency(), Instant.now());
			this.charges.add(charge);
			return charge;
		}, delayed);
	}

	public Optional<ProviderCharge> findByReference(String reference) {
		return this.charges.stream().filter(charge -> charge.reference().equals(reference)).findFirst();
	}

	public List<ProviderCharge> charges() {
		return List.copyOf(this.charges);
	}

	public boolean isUnavailable() {
		return this.unavailable;
	}

	public void configure(Duration latency, boolean unavailable) {
		this.latency = latency;
		this.unavailable = unavailable;
	}

	@Override
	public Mono<Void> reset() {
		return Mono.fromRunnable(() -> {
			configure(this.defaultLatency, false);
			this.charges.clear();
			this.byIdempotencyKey.clear();
			this.sequence.set(0);
		});
	}
}
