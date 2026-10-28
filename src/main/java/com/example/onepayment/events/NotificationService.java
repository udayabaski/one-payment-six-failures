package com.example.onepayment.events;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.example.onepayment.config.PaymentsProperties;
import com.example.onepayment.simulation.Resettable;
import com.example.onepayment.tracing.Span;
import com.example.onepayment.tracing.SpanKind;
import com.example.onepayment.tracing.Tracer;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Sends the payment receipt. Runs after the client already got its response, so its time
 * must not be counted as part of the payment request's latency.
 */
@Component
public class NotificationService implements InitializingBean, DisposableBean, Resettable {

	private final InMemoryEventBus bus;

	private final Tracer tracer;

	private final Duration latency;

	private final List<String> sentReceipts = new CopyOnWriteArrayList<>();

	private Disposable subscription;

	NotificationService(InMemoryEventBus bus, Tracer tracer, PaymentsProperties properties) {
		this.bus = bus;
		this.tracer = tracer;
		this.latency = properties.tracing().notificationLatency();
	}

	@Override
	public void afterPropertiesSet() {
		this.subscription = this.bus.subscribe().concatMap(this::handle).subscribe();
	}

	private Mono<Void> handle(Message<PaymentCompleted> message) {
		// No traceparent header? Then this span starts a brand-new, disconnected trace.
		var parent = Tracer.extract(message.headers()).orElse(null);
		Span span = this.tracer.startSpan("notification-service", "consume " + message.topic(),
				SpanKind.CONSUMER, parent);
		span.setAttribute("async", "true");
		return Mono.delay(this.latency)
			.doOnNext(tick -> this.sentReceipts.add(message.payload().paymentId()))
			.doFinally(signal -> span.end())
			.then()
			.onErrorResume(error -> Mono.empty());
	}

	public List<String> sentReceipts() {
		return List.copyOf(this.sentReceipts);
	}

	@Override
	public Mono<Void> reset() {
		return Mono.fromRunnable(this.sentReceipts::clear);
	}

	@Override
	public void destroy() {
		if (this.subscription != null) {
			this.subscription.dispose();
		}
	}
}
