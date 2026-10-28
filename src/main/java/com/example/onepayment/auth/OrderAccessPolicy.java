package com.example.onepayment.auth;

import com.example.onepayment.common.ApiException;
import com.example.onepayment.config.PaymentsProperties;
import com.example.onepayment.config.PaymentsProperties.ForeignOrderResponse;
import com.example.onepayment.orders.Order;
import com.example.onepayment.orders.OrderRepository;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Failure #1, the part no header can do for you: resource-level authorization.
 * A valid token for User A must not be able to pay for User B's order.
 */
@Component
public class OrderAccessPolicy {

	private final OrderRepository orders;

	private final ForeignOrderResponse foreignOrderResponse;

	public OrderAccessPolicy(OrderRepository orders, PaymentsProperties properties) {
		this.orders = orders;
		this.foreignOrderResponse = properties.authz().foreignOrderResponse();
	}

	public Mono<Order> loadPayableOrder(CallerIdentity caller, String orderId) {
		if (!caller.hasScope("payments:write")) {
			return Mono.error(new ApiException(HttpStatus.FORBIDDEN, "Insufficient scope",
					"The access token does not allow creating payments")
				.header(HttpHeaders.WWW_AUTHENTICATE,
						"Bearer realm=\"payments\", error=\"insufficient_scope\", scope=\"payments:write\""));
		}
		return this.orders.findById(orderId)
			.switchIfEmpty(Mono.error(() -> orderNotFound(orderId)))
			.flatMap(order -> order.ownerId().equals(caller.userId()) ? Mono.just(order)
					: Mono.error(foreignOrder(orderId)));
	}

	private ApiException foreignOrder(String orderId) {
		return switch (this.foreignOrderResponse) {
			case FORBIDDEN -> new ApiException(HttpStatus.FORBIDDEN, "Forbidden",
					"You are not allowed to pay for order " + orderId);
			// Indistinguishable from an order that does not exist, so nothing leaks.
			case NOT_FOUND -> orderNotFound(orderId);
		};
	}

	private static ApiException orderNotFound(String orderId) {
		return new ApiException(HttpStatus.NOT_FOUND, "Not Found", "Order " + orderId + " was not found");
	}
}
