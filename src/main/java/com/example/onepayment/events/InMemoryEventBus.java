package com.example.onepayment.events;

import java.time.Duration;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import org.springframework.stereotype.Component;

/** Stands in for Kafka / RabbitMQ / SQS. */
@Component
public class InMemoryEventBus {

	private final Sinks.Many<Message<PaymentCompleted>> sink = Sinks.many().multicast().onBackpressureBuffer();

	public void publish(Message<PaymentCompleted> message) {
		this.sink.emitNext(message, Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(1)));
	}

	public Flux<Message<PaymentCompleted>> subscribe() {
		return this.sink.asFlux();
	}
}
