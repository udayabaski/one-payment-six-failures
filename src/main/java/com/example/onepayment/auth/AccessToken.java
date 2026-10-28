package com.example.onepayment.auth;

import java.time.Instant;
import java.util.Set;

public record AccessToken(String value, String userId, Set<String> scopes, String audience, Instant expiresAt) {
}
