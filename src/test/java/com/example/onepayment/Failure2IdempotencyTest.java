package com.example.onepayment;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.onepayment.payments.PaymentRepository;
import com.example.onepayment.simulation.FaultInjector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Failure #2: the charge succeeds, the response is lost")
class Failure2IdempotencyTest extends ScenarioTest {

	@Autowired
	FaultInjector faults;

	@Autowired
	PaymentRepository payments;

	@Test
	@DisplayName("Idempotency-Key is required on POST /api/payments")
	void keyIsRequired() {
		postPayment("/api/payments", "token-user-a", null, PAYMENT_FOR_ORDER_123).expectStatus().isBadRequest();
	}

	@Test
	@DisplayName("NAIVE: crash after the provider charged, client retries, customer is charged twice")
	void naiveRetryAfterCrashChargesTwice() {
		this.faults.armCrashAfterProviderCharge(1);

		postPayment("/naive/f2/payments", "token-user-a", "pay-1", PAYMENT_FOR_ORDER_123)
			.expectStatus().is5xxServerError();
		postPayment("/naive/f2/payments", "token-user-a", "pay-1", PAYMENT_FOR_ORDER_123)
			.expectStatus().isCreated();

		assertThat(this.provider.charges()).hasSize(2);
		narrate("Provider charges for one click: %d", this.provider.charges().size());
	}

	@Test
	@DisplayName("FIXED: crash after the provider charged, retry recovers the original payment, one charge")
	void fixedRetryAfterCrashRecovers() {
		this.faults.armCrashAfterProviderCharge(1);

		// 1. The card is charged, then the payment service "dies" before saving the result.
		postPayment("/api/payments", "token-user-a", "pay-1", PAYMENT_FOR_ORDER_123)
			.expectStatus().is5xxServerError();
		assertThat(this.provider.charges()).hasSize(1);

		// 2. Immediate retry: the dead worker's lease has not expired yet.
		postPayment("/api/payments", "token-user-a", "pay-1", PAYMENT_FOR_ORDER_123)
			.expectStatus().isEqualTo(409)
			.expectHeader().exists("Retry-After");

		// 3. After the lease expires, the retry asks the provider what happened and finishes the job.
		pause(Duration.ofMillis(700));
		var recovered = postPayment("/api/payments", "token-user-a", "pay-1", PAYMENT_FOR_ORDER_123)
			.expectStatus().isCreated()
			.expectBody(Map.class)
			.returnResult()
			.getResponseBody();
		assertThat(recovered.get("providerChargeId")).isEqualTo(this.provider.charges().getFirst().chargeId());

		// 4. Later retries replay the stored response.
		postPayment("/api/payments", "token-user-a", "pay-1", PAYMENT_FOR_ORDER_123)
			.expectStatus().isCreated()
			.expectHeader().valueEquals("Idempotent-Replayed", "true")
			.expectBody(Map.class).isEqualTo(recovered);

		assertThat(this.provider.charges()).hasSize(1);
		assertThat(this.payments.countForOrder("ORDER-123").block()).isEqualTo(1);
		narrate("Provider charges for one click: %d (payment %s)", this.provider.charges().size(),
				recovered.get("paymentId"));
	}

	@Test
	@DisplayName("NAIVE: five concurrent retries with the same key all charge the card")
	void naiveConcurrentRetriesChargeRepeatedly() {
		this.provider.configure(Duration.ofMillis(300), false);

		concurrently(5, "/naive/f2/payments", "token-user-a", "pay-2", PAYMENT_FOR_ORDER_123);

		assertThat(this.provider.charges()).hasSizeGreaterThan(1);
		narrate("Check-then-act on an in-memory map: %d charges", this.provider.charges().size());
	}

	@Test
	@DisplayName("FIXED: five concurrent retries with the same key charge the card once")
	void fixedConcurrentRetriesChargeOnce() {
		this.provider.configure(Duration.ofMillis(300), false);

		List<ResponseEntity<Map>> responses = concurrently(5, "/api/payments", "token-user-a", "pay-2",
				PAYMENT_FOR_ORDER_123);

		assertThat(this.provider.charges()).hasSize(1);
		assertThat(responses).extracting(r -> r.getStatusCode().value()).isSubsetOf(Set.of(201, 409)).contains(201);
		narrate("Statuses: %s, provider charges: %d",
				responses.stream().map(r -> r.getStatusCode().value()).toList(), this.provider.charges().size());
	}

	@Test
	@DisplayName("FIXED: same key with a different payload is rejected with 422")
	void keyReuseWithDifferentPayloadIsRejected() {
		postPayment("/api/payments", "token-user-a", "pay-3", PAYMENT_FOR_ORDER_123).expectStatus().isCreated();

		postPayment("/api/payments", "token-user-a", "pay-3", """
				{"orderId": "ORDER-789", "amount": 2500, "currency": "USD"}""")
			.expectStatus().isEqualTo(422)
			.expectBody().jsonPath("$.title").isEqualTo("Idempotency-Key reused");

		assertThat(this.provider.charges()).hasSize(1);
	}

	@Test
	@DisplayName("FIXED: provider times out (outcome unknown), retry reconciles instead of charging again")
	void providerTimeoutIsReconciled() {
		// Our client gives up after 1s; the provider finishes the charge after 1.5s anyway.
		this.provider.configure(Duration.ofMillis(1500), false);

		postPayment("/api/payments", "token-user-a", "pay-4", PAYMENT_FOR_ORDER_123)
			.expectStatus().isEqualTo(503)
			.expectBody().jsonPath("$.title").isEqualTo("Payment outcome unknown");

		pause(Duration.ofMillis(800));
		assertThat(this.provider.charges()).hasSize(1);

		postPayment("/api/payments", "token-user-a", "pay-4", PAYMENT_FOR_ORDER_123).expectStatus().isCreated();
		assertThat(this.provider.charges()).hasSize(1);
	}
}
