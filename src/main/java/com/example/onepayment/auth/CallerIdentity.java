package com.example.onepayment.auth;

import java.util.Set;

/** Who the credentials say the caller is. Says nothing about which resources they may touch. */
public record CallerIdentity(String userId, Set<String> scopes) {

	public static final String ATTRIBUTE = "com.example.onepayment.auth.CallerIdentity";

	public boolean hasScope(String scope) {
		return this.scopes.contains(scope);
	}
}
