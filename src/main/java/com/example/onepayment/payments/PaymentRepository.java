package com.example.onepayment.payments;

import java.time.OffsetDateTime;

import com.example.onepayment.simulation.Resettable;
import com.example.onepayment.tracing.SpanKind;
import com.example.onepayment.tracing.Tracer;
import reactor.core.publisher.Mono;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

/** The payment ledger. */
@Repository
public class PaymentRepository implements Resettable {

	private final DatabaseClient db;

	private final Tracer tracer;

	PaymentRepository(DatabaseClient db, Tracer tracer) {
		this.db = db;
		this.tracer = tracer;
	}

	public Mono<Void> insert(String tenantId, PaymentResponse payment, OffsetDateTime createdAt) {
		Mono<Void> insert = this.db.sql("""
				INSERT INTO payments (payment_id, tenant_id, order_id, amount, currency, status, provider_charge_id, created_at)
				VALUES (:paymentId, :tenant, :orderId, :amount, :currency, :status, :chargeId, :createdAt)
				""")
			.bind("paymentId", payment.paymentId())
			.bind("tenant", tenantId)
			.bind("orderId", payment.orderId())
			.bind("amount", payment.amount())
			.bind("currency", payment.currency())
			.bind("status", payment.status())
			.bind("chargeId", payment.providerChargeId())
			.bind("createdAt", createdAt)
			.then();
		return this.tracer.inSpan("database", "INSERT payments", SpanKind.CLIENT, insert);
	}

	public Mono<Long> countForOrder(String orderId) {
		return this.db.sql("SELECT COUNT(*) AS n FROM payments WHERE order_id = :orderId")
			.bind("orderId", orderId)
			.map(row -> row.get("n", Long.class))
			.one();
	}

	@Override
	public Mono<Void> reset() {
		return this.db.sql("DELETE FROM payments").then();
	}
}
