package com.example.onepayment.ratelimit;

import java.net.InetSocketAddress;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Failure #4: deriving the client address from a trusted proxy chain")
class TrustedProxyClientIpResolverTest {

	// Our load balancer lives in 10.0.0.0/8.
	private final ClientIpResolver resolver = new TrustedProxyClientIpResolver(List.of(CidrRange.parse("10.0.0.0/8")));

	private final ClientIpResolver naive = new NaiveClientIpResolver();

	@Test
	@DisplayName("Direct connection from the internet: the peer is the client, headers are ignored")
	void directClientCannotChooseItsAddress() {
		var headers = xff("192.0.2.99");
		assertThat(this.resolver.resolve(peer("203.0.113.50"), headers)).isEqualTo("203.0.113.50");
		assertThat(this.naive.resolve(peer("203.0.113.50"), headers)).isEqualTo("192.0.2.99");
	}

	@Test
	@DisplayName("Via our proxy: the proxy appends the real client; attacker-prepended entries are skipped")
	void rightmostUntrustedEntryWins() {
		// Attacker sent "X-Forwarded-For: 192.0.2.99"; our proxy appended the real address.
		var headers = xff("192.0.2.99, 203.0.113.50");
		assertThat(this.resolver.resolve(peer("10.0.0.5"), headers)).isEqualTo("203.0.113.50");
		assertThat(this.naive.resolve(peer("10.0.0.5"), headers)).isEqualTo("192.0.2.99");
	}

	@Test
	@DisplayName("Several of our proxies in a row are all skipped")
	void multipleTrustedHops() {
		var headers = xff("198.51.100.1, 203.0.113.50, 10.0.1.7");
		assertThat(this.resolver.resolve(peer("10.0.0.5"), headers)).isEqualTo("203.0.113.50");
	}

	@Test
	@DisplayName("Standard Forwarded header (RFC 7239), including IPv6 and ports")
	void forwardedHeader() {
		var headers = new HttpHeaders();
		headers.add("Forwarded", "for=192.0.2.99;proto=https, for=\"[2001:db8:cafe::17]:4711\"");
		assertThat(this.resolver.resolve(peer("10.0.0.5"), headers)).isEqualTo("2001:db8:cafe:0:0:0:0:17");
	}

	@Test
	@DisplayName("Garbage or hostnames are never resolved; we stop at the last verified hop")
	void garbageStopsTheWalk() {
		var headers = xff("evil.example.com, 10.0.3.3");
		assertThat(this.resolver.resolve(peer("10.0.0.5"), headers)).isEqualTo("10.0.3.3");
	}

	private static InetSocketAddress peer(String ip) {
		return new InetSocketAddress(ip, 54321);
	}

	private static HttpHeaders xff(String value) {
		var headers = new HttpHeaders();
		headers.set("X-Forwarded-For", value);
		return headers;
	}
}
