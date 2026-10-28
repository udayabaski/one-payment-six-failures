package com.example.onepayment.payments;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.example.onepayment.simulation.Resettable;
import com.example.onepayment.tracing.SpanKind;
import com.example.onepayment.tracing.Tracer;
import io.r2dbc.spi.Readable;
import reactor.core.publisher.Mono;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

/**
 * Durable idempotency records. Every state change is a single conditional SQL statement,
 * so the database, not application code, decides who wins a race.
 */
@Repository
public class IdempotencyStore implements Resettable {

	private static final OffsetDateTime LEASE_RELEASED = OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

	private final DatabaseClient db;

	private final Tracer tracer;

	IdempotencyStore(DatabaseClient db, Tracer tracer) {
		this.db = db;
		this.tracer = tracer;
	}

	/**
	 * Atomically claims a key that was never seen before.
	 * @return {@code false} if another request already owns this key
	 */
	public Mono<Boolean> reserve(IdempotencyRecord r) {
		Mono<Boolean> insert = this.db.sql("""
				INSERT INTO idempotency_records
				    (tenant_id, idempotency_key, request_fingerprint, payment_id, provider_request_id,
				     status, locked_until, created_at, expires_at)
				VALUES (:tenant, :key, :fingerprint, :paymentId, :providerRequestId,
				        'PROCESSING', :lockedUntil, :createdAt, :expiresAt)
				""")
			.bind("tenant", r.tenantId())
			.bind("key", r.idempotencyKey())
			.bind("fingerprint", r.requestFingerprint())
			.bind("paymentId", r.paymentId())
			.bind("providerRequestId", r.providerRequestId())
			.bind("lockedUntil", r.lockedUntil())
			.bind("createdAt", r.createdAt())
			.bind("expiresAt", r.expiresAt())
			.fetch()
			.rowsUpdated()
			.map(rows -> rows == 1)
			.onErrorReturn(DataIntegrityViolationException.class, false);
		return traced("INSERT idempotency_records", insert);
	}

	public Mono<IdempotencyRecord> find(String tenantId, String key) {
		Mono<IdempotencyRecord> select = this.db
			.sql("SELECT * FROM idempotency_records WHERE tenant_id = :tenant AND idempotency_key = :key")
			.bind("tenant", tenantId)
			.bind("key", key)
			.map(IdempotencyStore::toRecord)
			.one();
		return traced("SELECT idempotency_records", select);
	}

	/**
	 * Takes over a PROCESSING record whose previous owner's lease ran out (it probably crashed).
	 * Only one caller can win, because the WHERE clause is evaluated atomically with the update.
	 */
	public Mono<Boolean> tryAcquireLease(IdempotencyRecord r, OffsetDateTime now, OffsetDateTime until) {
		Mono<Boolean> update = this.db.sql("""
				UPDATE idempotency_records SET locked_until = :until
				WHERE tenant_id = :tenant AND idempotency_key = :key
				  AND status = 'PROCESSING' AND locked_until < :now
				""")
			.bind("until", until)
			.bind("tenant", r.tenantId())
			.bind("key", r.idempotencyKey())
			.bind("now", now)
			.fetch()
			.rowsUpdated()
			.map(rows -> rows == 1);
		return traced("UPDATE idempotency_records (acquire lease)", update);
	}

	public Mono<Long> complete(IdempotencyRecord r, int responseStatus, String responseBody) {
		Mono<Long> update = this.db.sql("""
				UPDATE idempotency_records
				SET status = 'COMPLETED', response_status = :status, response_body = :body, locked_until = NULL
				WHERE tenant_id = :tenant AND idempotency_key = :key AND status = 'PROCESSING'
				""")
			.bind("status", responseStatus)
			.bind("body", responseBody)
			.bind("tenant", r.tenantId())
			.bind("key", r.idempotencyKey())
			.fetch()
			.rowsUpdated();
		return traced("UPDATE idempotency_records (complete)", update);
	}

	/** The outcome is unknown: keep the record, but let the next retry run recovery right away. */
	public Mono<Long> releaseLease(IdempotencyRecord r) {
		return this.db.sql("""
				UPDATE idempotency_records SET locked_until = :released
				WHERE tenant_id = :tenant AND idempotency_key = :key AND status = 'PROCESSING'
				""")
			.bind("released", LEASE_RELEASED)
			.bind("tenant", r.tenantId())
			.bind("key", r.idempotencyKey())
			.fetch()
			.rowsUpdated();
	}

	/** The provider confirmed nothing happened: forget the key so a retry starts fresh. */
	public Mono<Long> delete(IdempotencyRecord r) {
		return this.db
			.sql("DELETE FROM idempotency_records WHERE tenant_id = :tenant AND idempotency_key = :key AND status = 'PROCESSING'")
			.bind("tenant", r.tenantId())
			.bind("key", r.idempotencyKey())
			.fetch()
			.rowsUpdated();
	}

	private <T> Mono<T> traced(String statement, Mono<T> work) {
		return this.tracer.inSpan("database", statement, SpanKind.CLIENT, work);
	}

	private static IdempotencyRecord toRecord(Readable row) {
		return new IdempotencyRecord(row.get("tenant_id", String.class), row.get("idempotency_key", String.class),
				row.get("request_fingerprint", String.class), row.get("payment_id", String.class),
				row.get("provider_request_id", String.class),
				IdempotencyRecord.Status.valueOf(row.get("status", String.class)),
				row.get("response_status", Integer.class), row.get("response_body", String.class),
				row.get("locked_until", OffsetDateTime.class), row.get("created_at", OffsetDateTime.class),
				row.get("expires_at", OffsetDateTime.class));
	}

	@Override
	public Mono<Void> reset() {
		return this.db.sql("DELETE FROM idempotency_records").then();
	}
}
