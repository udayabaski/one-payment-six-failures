package com.example.onepayment.orders;

import java.util.Map;

import reactor.core.publisher.Mono;

import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

	private final Map<String, Order> orders = Map.of(
			"ORDER-123", new Order("ORDER-123", "user-a", 10_000, "USD"),
			"ORDER-789", new Order("ORDER-789", "user-a", 2_500, "USD"),
			"ORDER-456", new Order("ORDER-456", "user-b", 10_000, "USD"));

	public Mono<Order> findById(String orderId) {
		return Mono.justOrEmpty(this.orders.get(orderId));
	}
}
