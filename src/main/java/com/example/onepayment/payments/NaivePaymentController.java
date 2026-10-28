package com.example.onepayment.payments;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.example.onepayment.provider.PaymentProviderClient;
import com.example.onepayment.provider.ProviderChargeRequest;
import com.example.onepayment.simulation.FaultInjector;
import com.example.onepayment.simulation.Resettable;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Failure #2, the tempting version. It even reads {@code Idempotency-Key}, but:
 * <ul>
 * <li>the key is checked and stored in two separate steps (check-then-act), so concurrent
 * retries both charge;</li>
 * <li>the key is stored only after the charge, so a crash in between forgets it;</li>
 * <li>the provider gets no idempotency key of its own, so it can't de-duplicate either;</li>
 * <li>the map lives in memory, so a restart or a second instance forgets everything.</li>
 * </ul>
 * DO NOT COPY THIS.
 */
@RestController
class NaivePaymentController implements Resettable {

	private final Map<String, PaymentResponse> completed = new ConcurrentHashMap<>();

	private final PaymentProviderClient provider;

	private final FaultInjector faults;

	NaivePaymentController(PaymentProviderClient provider, FaultInjector faults) {
		this.provider = provider;
		this.faults = faults;
	}

	@PostMapping("/naive/f2/payments")
	Mono<ResponseEntity<PaymentResponse>> create(
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@RequestBody PaymentRequest request) {
		if (idempotencyKey != null && this.completed.containsKey(idempotencyKey)) {
			return Mono.just(ResponseEntity.ok(this.completed.get(idempotencyKey)));
		}
		String paymentId = "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		return this.provider.charge(null, new ProviderChargeRequest(paymentId, request.amount(), request.currency()))
			.doOnNext(charge -> this.faults.crashIfArmedAfterProviderCharge())
			.map(charge -> {
				var payment = new PaymentResponse(paymentId, request.orderId(), request.amount(), request.currency(),
						"SUCCEEDED", charge.chargeId());
				if (idempotencyKey != null) {
					this.completed.put(idempotencyKey, payment);
				}
				return ResponseEntity.status(HttpStatus.CREATED).body(payment);
			});
	}

	@Override
	public Mono<Void> reset() {
		return Mono.fromRunnable(this.completed::clear);
	}
}
