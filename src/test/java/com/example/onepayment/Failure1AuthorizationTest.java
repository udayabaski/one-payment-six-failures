package com.example.onepayment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Failure #1: the token is valid, the payment is still unauthorized")
class Failure1AuthorizationTest extends ScenarioTest {

	private static final String PAYMENT_FOR_USER_B_ORDER = """
			{"orderId": "ORDER-456", "amount": 10000, "currency": "USD"}""";

	@Test
	@DisplayName("No credentials: 401 with a bare Bearer challenge")
	void missingTokenIsChallenged() {
		this.http.post()
			.uri("/api/payments")
			.header("Idempotency-Key", "\"k-1\"")
			.header("Content-Type", "application/json")
			.bodyValue(PAYMENT_FOR_ORDER_123)
			.exchange()
			.expectStatus().isUnauthorized()
			.expectHeader().valueEquals("WWW-Authenticate", "Bearer realm=\"payments\"");
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "token-expired", "token-revoked", "token-wrong-audience", "token-made-up" })
	@DisplayName("Unusable token: 401 with error=\"invalid_token\"")
	void unusableTokenIsRejected(String token) {
		postPayment("/api/payments", token, "k-1", PAYMENT_FOR_ORDER_123)
			.expectStatus().isUnauthorized()
			.expectHeader().value("WWW-Authenticate", challenge -> assertThat(challenge).contains("error=\"invalid_token\""));
	}

	@Test
	@DisplayName("Valid token without payments:write scope: 403 with error=\"insufficient_scope\"")
	void readOnlyTokenCannotPay() {
		postPayment("/api/payments", "token-read-only", "k-1", PAYMENT_FOR_ORDER_123)
			.expectStatus().isForbidden()
			.expectHeader().value("WWW-Authenticate", challenge -> assertThat(challenge).contains("insufficient_scope"));
	}

	@Test
	@DisplayName("NAIVE: User A's valid token pays for User B's order")
	void naiveEndpointLetsUserAPayForUserBsOrder() {
		postPayment("/naive/f1/payments", "token-user-a", null, PAYMENT_FOR_USER_B_ORDER)
			.expectStatus().isCreated();

		assertThat(this.provider.charges()).hasSize(1);
		narrate("Authentication passed, so the naive endpoint charged ORDER-456 (owned by user-b) for user-a.");
	}

	@Test
	@DisplayName("FIXED: resource-level authorization rejects User A paying for User B's order")
	void fixedEndpointChecksOrderOwnership() {
		postPayment("/api/payments", "token-user-a", "k-1", PAYMENT_FOR_USER_B_ORDER)
			.expectStatus().isForbidden();

		assertThat(this.provider.charges()).isEmpty();
	}

	@Test
	@DisplayName("LIMIT: a stolen but valid token is indistinguishable from the real user")
	void stolenTokenStillWorks() {
		this.http.post()
			.uri("/api/payments")
			.headers(headers -> {
				headers.setBearerAuth("token-user-a");
				headers.set("Idempotency-Key", "\"attacker-1\"");
				headers.set("User-Agent", "definitely-not-user-a/1.0");
			})
			.header("Content-Type", "application/json")
			.bodyValue(PAYMENT_FOR_ORDER_123)
			.exchange()
			.expectStatus().isCreated();

		narrate("Authorization carries credentials; it cannot tell who is holding them. That needs "
				+ "expiry, revocation, sender-constrained tokens and risk controls.");
	}
}
