package com.example.onepayment.tracing;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Failure #6: W3C traceparent and baggage handling")
class TraceContextTest {

	@Test
	@DisplayName("Parses the example from the article")
	void parsesValidHeader() {
		var context = TraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01").orElseThrow();
		assertThat(context.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
		assertThat(context.spanId()).isEqualTo("00f067aa0ba902b7");
		assertThat(context.sampled()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
			"00-00000000000000000000000000000000-00f067aa0ba902b7-01",
			"00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
			"00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01", "garbage" })
	@DisplayName("Rejects invalid version, all-zero ids, uppercase hex and garbage")
	void rejectsInvalidHeaders(String header) {
		assertThat(TraceContext.parse(header)).isEmpty();
	}

	@Test
	@DisplayName("A child keeps the trace id and gets a new span id")
	void childSpan() {
		var parent = TraceContext.newRoot();
		var child = parent.newChild();
		assertThat(child.traceId()).isEqualTo(parent.traceId());
		assertThat(child.spanId()).isNotEqualTo(parent.spanId());
	}

	@Test
	@DisplayName("Baggage keeps only allow-listed keys")
	void baggageAllowlist() {
		var filtered = Baggage.parse("client.app=ios,card.number=4111;prop=1").filter(Set.of("client.app"));
		assertThat(filtered.baggage().toHeader()).isEqualTo("client.app=ios");
		assertThat(filtered.droppedKeys()).containsExactly("card.number");
	}
}
