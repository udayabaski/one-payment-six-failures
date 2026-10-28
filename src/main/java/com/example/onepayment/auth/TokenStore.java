package com.example.onepayment.auth;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Stands in for an authorization server / token introspection endpoint.
 * Tokens are opaque strings so the demo stays readable with curl.
 */
@Component
public class TokenStore {

	public static final String AUDIENCE = "payments-api";

	private static final Instant FAR_FUTURE = Instant.parse("2999-01-01T00:00:00Z");

	private static final Set<String> ALL_SCOPES = Set.of("payments:read", "payments:write", "refunds:write");

	private final Map<String, AccessToken> tokens = new ConcurrentHashMap<>();

	private final Set<String> revoked = ConcurrentHashMap.newKeySet();

	public TokenStore() {
		add(new AccessToken("token-user-a", "user-a", ALL_SCOPES, AUDIENCE, FAR_FUTURE));
		add(new AccessToken("token-user-b", "user-b", ALL_SCOPES, AUDIENCE, FAR_FUTURE));
		add(new AccessToken("token-operator-a", "operator-a", ALL_SCOPES, AUDIENCE, FAR_FUTURE));
		add(new AccessToken("token-operator-b", "operator-b", ALL_SCOPES, AUDIENCE, FAR_FUTURE));
		add(new AccessToken("token-expired", "user-a", ALL_SCOPES, AUDIENCE, Instant.parse("2020-01-01T00:00:00Z")));
		add(new AccessToken("token-read-only", "user-a", Set.of("payments:read"), AUDIENCE, FAR_FUTURE));
		add(new AccessToken("token-wrong-audience", "user-a", ALL_SCOPES, "reporting-api", FAR_FUTURE));
		add(new AccessToken("token-revoked", "user-a", ALL_SCOPES, AUDIENCE, FAR_FUTURE));
		this.revoked.add("token-revoked");
	}

	public Optional<AccessToken> find(String value) {
		return Optional.ofNullable(this.tokens.get(value));
	}

	public boolean isRevoked(String value) {
		return this.revoked.contains(value);
	}

	public AccessToken issue(String userId) {
		AccessToken token = new AccessToken("token-" + UUID.randomUUID(), userId, ALL_SCOPES, AUDIENCE, FAR_FUTURE);
		add(token);
		return token;
	}

	private void add(AccessToken token) {
		this.tokens.put(token.value(), token);
	}
}
