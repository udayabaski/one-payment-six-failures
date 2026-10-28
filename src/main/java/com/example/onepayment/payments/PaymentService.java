package com.example.onepayment.payments;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.example.onepayment.auth.CallerIdentity;
import com.example.onepayment.auth.OrderAccessPolicy;
import com.example.onepayment.common.ApiException;
import com.example.onepayment.config.PaymentsProperties;
import com.example.onepayment.events.PaymentCompleted;
import com.example.onepayment.events.PaymentEventPublisher;
import com.example.onepayment.orders.Order;
import com.example.onepayment.provider.PaymentProviderClient;
import com.example.onepayment.provider.ProviderCharge;
import com.example.onepayment.provider.ProviderChargeRequest;
import com.example.onepayment.provider.ProviderOutcomeUnknownException;
import com.example.onepayment.provider.ProviderUnavailableException;
import com.example.onepayment.simulation.FaultInjector;
import com.example.onepayment.tracing.SpanKind;
import com.example.onepayment.tracing.Tracer;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * Failure #2: the idempotent payment flow.
 *
 * <pre>
 *  reserve key (INSERT, unique) ──▶ call provider with a stable provider key ──▶ save result + response
 *          │ already exists
 *          ├─ different payload ─────────▶ 422
 *          ├─ COMPLETED ─────────────────▶ replay the stored response
 *          ├─ PROCESSING, lease held ────▶ 409 + Retry-After
 *          └─ PROCESSING, lease expired ─▶ recover: ask the provider what happened, then finish
 * </pre>
 */
@Service
public class PaymentService {

	private static final String SERVICE = "payments-api";

	private final OrderAccessPolicy orderAccess;

	private final IdempotencyStore idempotency;

	private final PaymentRepository payments;

	private final PaymentProviderClient provider;

	private final PaymentEventPublisher events;

	private final FaultInjector faults;

	private final TransactionalOperator transactions;

	private final Tracer tracer;

	private final JsonMapper json;

	private final Clock clock;

	private final PaymentsProperties properties;

	PaymentService(OrderAccessPolicy orderAccess, IdempotencyStore idempotency, PaymentRepository payments,
			PaymentProviderClient provider, PaymentEventPublisher events, FaultInjector faults,
			TransactionalOperator transactions, Tracer tracer, JsonMapper json, Clock clock,
			PaymentsProperties properties) {
		this.orderAccess = orderAccess;
		this.idempotency = idempotency;
		this.payments = payments;
		this.provider = provider;
		this.events = events;
		this.faults = faults;
		this.transactions = transactions;
		this.tracer = tracer;
		this.json = json;
		this.clock = clock;
		this.properties = properties;
	}

	public Mono<StoredResponse> pay(CallerIdentity caller, String idempotencyKeyHeader, PaymentRequest request) {
		String key = IdempotencyKeys.parse(idempotencyKeyHeader);
		return this.tracer
			.inSpan(SERVICE, "order-service authorize-order", SpanKind.INTERNAL,
					this.orderAccess.loadPayableOrder(caller, request.orderId()))
			.doOnNext(order -> requireMatchingAmount(order, request))
			.flatMap(order -> this.tracer.inSpan(SERVICE, "payment-service process-payment", SpanKind.INTERNAL,
					Mono.defer(() -> process(caller.userId(), key, request))));
	}

	private Mono<StoredResponse> process(String tenantId, String key, PaymentRequest request) {
		String fingerprint = IdempotencyKeys.fingerprint(request);
		OffsetDateTime now = now();
		String paymentId = "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		// Persist our operation identity and the provider reference BEFORE the irreversible call.
		var fresh = new IdempotencyRecord(tenantId, key, fingerprint, paymentId, "prq_" + paymentId,
				IdempotencyRecord.Status.PROCESSING, null, null, now.plus(lease()), now,
				now.plus(this.properties.idempotency().retention()));
		return this.idempotency.reserve(fresh)
			.flatMap(reserved -> reserved ? chargeAndRecord(fresh, request)
					: this.idempotency.find(tenantId, key)
						.switchIfEmpty(Mono.error(this::inProgress))
						.flatMap(existing -> handleDuplicate(existing, fingerprint, request)));
	}

	private Mono<StoredResponse> handleDuplicate(IdempotencyRecord existing, String fingerprint,
			PaymentRequest request) {
		if (!existing.requestFingerprint().equals(fingerprint)) {
			return Mono.error(new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency-Key reused",
					"This Idempotency-Key was already used with a different request payload"));
		}
		return switch (existing.status()) {
			case COMPLETED -> Mono.just(new StoredResponse(existing.responseStatus(), existing.responseBody(), true));
			case PROCESSING -> this.idempotency.tryAcquireLease(existing, now(), now().plus(lease()))
				.flatMap(acquired -> acquired ? recover(existing, request) : Mono.error(inProgress()));
		};
	}

	private Mono<StoredResponse> chargeAndRecord(IdempotencyRecord record, PaymentRequest request) {
		var providerRequest = new ProviderChargeRequest(record.paymentId(), request.amount(), request.currency());
		return this.provider.charge(record.providerRequestId(), providerRequest)
			// The card is charged now. A crash here leaves the record PROCESSING with no result.
			.doOnNext(charge -> this.faults.crashIfArmedAfterProviderCharge())
			.flatMap(charge -> recordSuccess(record, request, charge))
			.onErrorResume(ProviderUnavailableException.class,
					error -> this.idempotency.delete(record)
						.then(Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Payment provider unavailable",
								"The payment was not attempted. Retry later with the same Idempotency-Key.")
							.retryAfter(this.properties.retryAfter().providerUnavailable()))))
			.onErrorResume(ProviderOutcomeUnknownException.class,
					error -> this.idempotency.releaseLease(record)
						.then(Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Payment outcome unknown",
								"We could not confirm the payment. Retry with the same Idempotency-Key to resolve it.")
							.retryAfter(this.properties.retryAfter().inProgress()))));
	}

	/**
	 * The previous attempt died somewhere between "reserved" and "completed". The database can't
	 * tell us whether the card was charged; only the provider can.
	 */
	private Mono<StoredResponse> recover(IdempotencyRecord record, PaymentRequest request) {
		Mono<StoredResponse> recovery = this.provider.findByReference(record.paymentId())
			.flatMap(charge -> recordSuccess(record, request, charge))
			// Not found: the charge never happened. The stable provider key keeps a retry safe anyway.
			.switchIfEmpty(Mono.defer(() -> chargeAndRecord(record, request)));
		return this.tracer.inSpan(SERVICE, "payment-service recover-uncertain-payment", SpanKind.INTERNAL, recovery);
	}

	private Mono<StoredResponse> recordSuccess(IdempotencyRecord record, PaymentRequest request,
			ProviderCharge charge) {
		var payment = new PaymentResponse(record.paymentId(), request.orderId(), request.amount(), request.currency(),
				"SUCCEEDED", charge.chargeId());
		String body = this.json.writeValueAsString(payment);
		// Ledger row and idempotency result commit together, or not at all.
		Mono<Void> save = this.payments.insert(record.tenantId(), payment, now())
			.then(this.idempotency.complete(record, HttpStatus.CREATED.value(), body))
			.then()
			.as(this.transactions::transactional);
		// Published after commit. If we crash in between, the event is lost: production systems
		// use a transactional outbox for this.
		return save
			.then(this.events.publish(new PaymentCompleted(payment.paymentId(), payment.orderId(), payment.amount(),
					payment.currency())))
			.thenReturn(new StoredResponse(HttpStatus.CREATED.value(), body, false));
	}

	private static void requireMatchingAmount(Order order, PaymentRequest request) {
		if (order.amount() != request.amount() || !order.currency().equals(request.currency())) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "Amount mismatch",
					"Order " + order.orderId() + " is " + order.amount() + " " + order.currency());
		}
	}

	private ApiException inProgress() {
		return new ApiException(HttpStatus.CONFLICT, "Payment in progress",
				"A request with this Idempotency-Key is still being processed")
			.retryAfter(this.properties.retryAfter().inProgress());
	}

	private Duration lease() {
		return this.properties.idempotency().lease();
	}

	private OffsetDateTime now() {
		return OffsetDateTime.now(this.clock);
	}
}
