package com.example.onepayment.payments;

import com.example.onepayment.auth.CallerIdentity;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The production-shaped endpoint: authorization + durable idempotency + recovery. */
@RestController
class PaymentController {

	private final PaymentService payments;

	PaymentController(PaymentService payments) {
		this.payments = payments;
	}

	@PostMapping("/api/payments")
	Mono<ResponseEntity<String>> create(@RequestAttribute(CallerIdentity.ATTRIBUTE) CallerIdentity caller,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody PaymentRequest request) {
		return this.payments.pay(caller, idempotencyKey, request).map(stored -> {
			var response = ResponseEntity.status(stored.status()).contentType(MediaType.APPLICATION_JSON);
			if (stored.replayed()) {
				// A common convention (not part of the draft) to tell clients this is a replay.
				response.header("Idempotent-Replayed", "true");
			}
			return response.body(stored.body());
		});
	}
}
