package com.example.onepayment.refunds;

import com.example.onepayment.simulation.Resettable;
import io.r2dbc.spi.Readable;
import reactor.core.publisher.Mono;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

@Repository
public class RefundRepository implements Resettable {

	private final DatabaseClient db;

	RefundRepository(DatabaseClient db) {
		this.db = db;
	}

	public Mono<Refund> findById(String id) {
		return this.db.sql("SELECT * FROM refunds WHERE id = :id").bind("id", id).map(RefundRepository::toRefund).one();
	}

	/**
	 * Optimistic concurrency done right: the version check and the write are ONE statement.
	 * @return 1 if we won, 0 if someone else changed the row first
	 */
	public Mono<Long> updateStatusIfVersion(String id, RefundStatus status, long expectedVersion, String operator) {
		return this.db.sql("""
				UPDATE refunds SET status = :status, version = version + 1, updated_by = :operator
				WHERE id = :id AND version = :expectedVersion
				""")
			.bind("status", status.name())
			.bind("operator", operator)
			.bind("id", id)
			.bind("expectedVersion", expectedVersion)
			.fetch()
			.rowsUpdated();
	}

	/** Last write wins. Used only by the naive endpoints. */
	public Mono<Long> updateStatusBlindly(String id, RefundStatus status, String operator) {
		return this.db.sql("""
				UPDATE refunds SET status = :status, version = version + 1, updated_by = :operator WHERE id = :id
				""")
			.bind("status", status.name())
			.bind("operator", operator)
			.bind("id", id)
			.fetch()
			.rowsUpdated();
	}

	private static Refund toRefund(Readable row) {
		return new Refund(row.get("id", String.class), row.get("payment_id", String.class),
				row.get("amount", Long.class), row.get("currency", String.class),
				RefundStatus.valueOf(row.get("status", String.class)), row.get("version", Long.class),
				row.get("updated_by", String.class));
	}

	/** Back to the article's starting point: REF-123, $100, PENDING, version 7. */
	@Override
	public Mono<Void> reset() {
		return this.db.sql("DELETE FROM refunds")
			.then()
			.then(this.db.sql("""
					INSERT INTO refunds (id, payment_id, amount, currency, status, version, updated_by)
					VALUES ('REF-123', 'PAY-123', 10000, 'USD', 'PENDING', 7, NULL)
					""").then());
	}
}
