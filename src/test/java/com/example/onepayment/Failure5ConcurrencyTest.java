package com.example.onepayment;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Failure #5: two operators issue conflicting refund updates")
class Failure5ConcurrencyTest extends ScenarioTest {

	@Test
	@DisplayName("GET returns the version validator: ETag \"refund-v7\"")
	void getReturnsETag() {
		this.http.get()
			.uri("/api/refunds/REF-123")
			.headers(h -> h.setBearerAuth("token-operator-a"))
			.exchange()
			.expectStatus().isOk()
			.expectHeader().valueEquals("ETag", "\"refund-v7\"");
	}

	@Test
	@DisplayName("NAIVE: last write wins, operator A's approval silently disappears")
	void naiveBlindOverwriteLosesAnUpdate() {
		patch("/naive/f5/refunds/REF-123", "token-operator-a", null, "APPROVED").expectStatus().isOk();
		patch("/naive/f5/refunds/REF-123", "token-operator-b", null, "REJECTED").expectStatus().isOk();

		assertThat(currentStatus()).isEqualTo("REJECTED");
		narrate("Operator A approved, operator B (looking at a stale screen) rejected. Final: REJECTED.");
	}

	@Test
	@DisplayName("FIXED: the stale If-Match gets 412, operator A's decision stands")
	void fixedStaleUpdateIsRejected() {
		patch("/api/refunds/REF-123", "token-operator-a", "\"refund-v7\"", "APPROVED")
			.expectStatus().isOk()
			.expectHeader().valueEquals("ETag", "\"refund-v8\"");
		patch("/api/refunds/REF-123", "token-operator-b", "\"refund-v7\"", "REJECTED")
			.expectStatus().isEqualTo(412)
			.expectHeader().valueEquals("ETag", "\"refund-v8\"");

		assertThat(currentStatus()).isEqualTo("APPROVED");
	}

	@Test
	@DisplayName("FIXED: omitting If-Match is not allowed (428 Precondition Required)")
	void missingIfMatchIsRejected() {
		patch("/api/refunds/REF-123", "token-operator-a", null, "APPROVED").expectStatus().isEqualTo(428);
	}

	@Test
	@DisplayName("FIXED: even with a fresh ETag, business rules still apply (APPROVED is final)")
	void decisionsAreFinal() {
		patch("/api/refunds/REF-123", "token-operator-a", "\"refund-v7\"", "APPROVED").expectStatus().isOk();
		patch("/api/refunds/REF-123", "token-operator-b", "\"refund-v8\"", "REJECTED").expectStatus().isEqualTo(409);
	}

	@Test
	@DisplayName("NAIVE: If-Match checked in Java, then a blind UPDATE: simultaneous requests both win")
	void naiveCheckThenActRaces() {
		List<Integer> statuses = simultaneously("/naive/f5/refunds/REF-123/check-then-act");

		assertThat(statuses).containsExactly(200, 200);
		narrate("Both requests carried If-Match \"refund-v7\" and both succeeded: %s", statuses);
	}

	@Test
	@DisplayName("FIXED: UPDATE ... WHERE version = 7 lets exactly one simultaneous request win")
	void fixedAtomicVersionCheck() {
		List<Integer> statuses = simultaneously("/api/refunds/REF-123");

		assertThat(statuses).containsExactlyInAnyOrder(200, 412);
		narrate("Same race against the atomic version check: %s", statuses);
	}

	private List<Integer> simultaneously(String path) {
		return Flux.just(new String[] { "token-operator-a", "APPROVED" }, new String[] { "token-operator-b", "REJECTED" })
			.flatMap(op -> this.web.patch()
				.uri(path)
				.headers(h -> {
					h.setBearerAuth(op[0]);
					h.setIfMatch("\"refund-v7\"");
				})
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("status", op[1]))
				.exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode().value())))
			.collectSortedList()
			.block(Duration.ofSeconds(10));
	}

	private WebTestClient.ResponseSpec patch(String path, String token, String ifMatch, String status) {
		return this.http.patch()
			.uri(path)
			.headers(h -> {
				h.setBearerAuth(token);
				if (ifMatch != null) {
					h.setIfMatch(ifMatch);
				}
			})
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(Map.of("status", status))
			.exchange();
	}

	private String currentStatus() {
		return (String) this.http.get()
			.uri("/api/refunds/REF-123")
			.headers(h -> h.setBearerAuth("token-operator-a"))
			.exchange()
			.expectBody(Map.class)
			.returnResult()
			.getResponseBody()
			.get("status");
	}
}
