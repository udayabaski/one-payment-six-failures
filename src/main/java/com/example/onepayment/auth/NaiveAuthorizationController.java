package com.example.onepayment.auth;

import java.util.UUID;

import com.example.onepayment.common.ApiException;
import com.example.onepayment.orders.OrderRepository;
import com.example.onepayment.payments.PaymentRequest;
import com.example.onepayment.payments.PaymentResponse;
import com.example.onepayment.provider.PaymentProviderClient;
import com.example.onepayment.provider.ProviderChargeRequest;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Failure #1, the tempting version: "the gateway already checked the token, so we're safe".
 * It authenticates the caller and then charges any order it is given. DO NOT COPY THIS.
 */
@RestController
class NaiveAuthorizationController {

	private final OrderRepository orders;

	private final PaymentProviderClient provider;

	NaiveAuthorizationController(OrderRepository orders, PaymentProviderClient provider) {
		this.orders = orders;
		this.provider = provider;
	}

	@PostMapping("/naive/f1/payments")
	Mono<ResponseEntity<PaymentResponse>> create(@RequestAttribute(CallerIdentity.ATTRIBUTE) CallerIdentity caller,
			@RequestBody PaymentRequest request) {
		// Missing: scope check, and "does this order belong to the caller?"
		return this.orders.findById(request.orderId())
			.switchIfEmpty(Mono.error(() -> new ApiException(HttpStatus.NOT_FOUND, "Not Found", "No such order")))
			.flatMap(order -> {
				String paymentId = "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
				return this.provider.charge(null, new ProviderChargeRequest(paymentId, order.amount(), order.currency()))
					.map(charge -> ResponseEntity.status(HttpStatus.CREATED)
						.body(new PaymentResponse(paymentId, order.orderId(), order.amount(), order.currency(),
								"SUCCEEDED (charged by " + caller.userId() + ", owned by " + order.ownerId() + ")",
								charge.chargeId())));
			});
	}
}
