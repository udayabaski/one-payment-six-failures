package com.example.onepayment.provider;

import reactor.core.publisher.Mono;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The provider's public HTTP API. Our payment service talks to it over real HTTP. */
@RestController
@RequestMapping("/provider/v1/charges")
class FakePaymentProviderController {

	private final FakePaymentProvider provider;

	FakePaymentProviderController(FakePaymentProvider provider) {
		this.provider = provider;
	}

	@PostMapping
	Mono<ResponseEntity<ProviderCharge>> charge(@RequestHeader(name = "Idempotency-Key", required = false) String key,
			@RequestBody ProviderChargeRequest request) {
		if (this.provider.isUnavailable()) {
			// The provider documents that a 503 means the charge was NOT attempted.
			return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.header(HttpHeaders.RETRY_AFTER, "30")
				.build());
		}
		return this.provider.charge(key, request).map(ResponseEntity::ok);
	}

	@GetMapping
	ResponseEntity<ProviderCharge> findByReference(@RequestParam String reference) {
		return ResponseEntity.of(this.provider.findByReference(reference));
	}
}
