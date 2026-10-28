package com.example.onepayment.auth;

import java.time.Clock;

import com.example.onepayment.tracing.SpanKind;
import com.example.onepayment.tracing.Tracer;
import reactor.core.publisher.Mono;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

/**
 * Failure #1, the "API gateway" step: validates the bearer token and answers
 * {@code 401} with a {@code WWW-Authenticate} challenge (RFC 6750) when it is unusable.
 * <p>
 * This filter only answers "who is calling?". It does not and cannot decide whether that
 * caller may pay for a particular order; see {@link OrderAccessPolicy}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class BearerAuthenticationFilter implements WebFilter {

	private static final String REALM = "Bearer realm=\"payments\"";

	private final TokenStore tokens;

	private final Clock clock;

	private final Tracer tracer;

	BearerAuthenticationFilter(TokenStore tokens, Clock clock, Tracer tracer) {
		this.tokens = tokens;
		this.clock = clock;
		this.tracer = tracer;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		String path = exchange.getRequest().getPath().value();
		boolean protectedPath = (path.startsWith("/api/") || path.startsWith("/naive/")) && !path.endsWith("/login");
		if (!protectedPath) {
			return chain.filter(exchange);
		}
		String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
		return this.tracer
			.inSpan("payments-api", "api-gateway authenticate", SpanKind.INTERNAL,
					Mono.fromSupplier(() -> authenticate(authorization)))
			.flatMap(result -> switch (result) {
				case Authenticated(CallerIdentity caller) -> {
					exchange.getAttributes().put(CallerIdentity.ATTRIBUTE, caller);
					yield chain.filter(exchange);
				}
				case Rejected(String challenge) -> reject(exchange.getResponse(), challenge);
			});
	}

	private AuthResult authenticate(String authorization) {
		if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
			// No credentials at all: a bare challenge, no error code (RFC 6750 section 3.1).
			return new Rejected(REALM);
		}
		String value = authorization.substring(7).trim();
		var token = this.tokens.find(value).orElse(null);
		if (token == null) {
			return invalidToken("The access token is not recognized");
		}
		if (this.tokens.isRevoked(value)) {
			return invalidToken("The access token was revoked");
		}
		if (!token.expiresAt().isAfter(this.clock.instant())) {
			return invalidToken("The access token expired");
		}
		if (!TokenStore.AUDIENCE.equals(token.audience())) {
			return invalidToken("The access token was not issued for this API");
		}
		return new Authenticated(new CallerIdentity(token.userId(), token.scopes()));
	}

	private static Rejected invalidToken(String description) {
		return new Rejected(REALM + ", error=\"invalid_token\", error_description=\"" + description + "\"");
	}

	private static Mono<Void> reject(ServerHttpResponse response, String challenge) {
		response.setStatusCode(HttpStatus.UNAUTHORIZED);
		response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, challenge);
		return response.setComplete();
	}

	private sealed interface AuthResult permits Authenticated, Rejected {
	}

	private record Authenticated(CallerIdentity caller) implements AuthResult {
	}

	private record Rejected(String challenge) implements AuthResult {
	}
}
