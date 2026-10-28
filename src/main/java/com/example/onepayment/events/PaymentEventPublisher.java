package com.example.onepayment.events;

import java.util.HashMap;
import java.util.Map;

import com.example.onepayment.tracing.SpanKind;
import com.example.onepayment.tracing.Tracer;
import com.example.onepayment.tracing.TracingSettings;
import reactor.core.publisher.Mono;

import org.springframework.stereotype.Component;

/**
 * Failure #6: HTTP trace propagation stops at the broker unless the producer copies the
 * context into the message headers.
 */
@Component
public class PaymentEventPublisher {

	public static final String TOPIC = "payment.completed";

	private final InMemoryEventBus bus;

	private final Tracer tracer;

	private final TracingSettings tracingSettings;

	PaymentEventPublisher(InMemoryEventBus bus, Tracer tracer, TracingSettings tracingSettings) {
		this.bus = bus;
		this.tracer = tracer;
		this.tracingSettings = tracingSettings;
	}

	public Mono<Void> publish(PaymentCompleted event) {
		Mono<Void> send = Mono.deferContextual(context -> {
			Map<String, String> headers = new HashMap<>();
			if (this.tracingSettings.propagateOverMessaging()) {
				Tracer.inject(context, headers::put);
			}
			this.bus.publish(new Message<>(TOPIC, Map.copyOf(headers), event));
			return Mono.empty();
		});
		return this.tracer.inSpan("payments-api", "publish " + TOPIC, SpanKind.PRODUCER, send);
	}
}
