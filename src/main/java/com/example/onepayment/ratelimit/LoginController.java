package com.example.onepayment.ratelimit;

import java.time.Clock;
import java.util.Map;

import com.example.onepayment.auth.TokenStore;
import com.example.onepayment.config.PaymentsProperties;
import com.example.onepayment.simulation.Resettable;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/**
 * Failure #4: an unauthenticated endpoint protected by an IP-based rate limit.
 * Both endpoints use the same limiter code and send the same RateLimit headers.
 * The only difference is how they decide who the client is.
 */
@RestController
class LoginController implements Resettable {

	private static final String POLICY = "login";

	private static final Map<String, String> USERS = Map.of("alice", "correct-horse-battery-staple");

	private final TokenStore tokens;

	private final FixedWindowRateLimiter limiter;

	private final FixedWindowRateLimiter naiveLimiter;

	private final ClientIpResolver resolver;

	private final ClientIpResolver naiveResolver = new NaiveClientIpResolver();

	LoginController(TokenStore tokens, PaymentsProperties properties, Clock clock) {
		this.tokens = tokens;
		var login = properties.rateLimit().login();
		this.limiter = new FixedWindowRateLimiter(login.limit(), login.window(), clock);
		this.naiveLimiter = new FixedWindowRateLimiter(login.limit(), login.window(), clock);
		this.resolver = new TrustedProxyClientIpResolver(
				properties.proxy().trustedProxies().stream().map(CidrRange::parse).toList());
	}

	record LoginRequest(String username, String password) {
	}

	@PostMapping("/api/login")
	Mono<ResponseEntity<?>> login(@RequestBody LoginRequest request, ServerWebExchange exchange) {
		return Mono.just(handle(request, exchange, this.resolver, this.limiter));
	}

	@PostMapping("/naive/f4/login")
	Mono<ResponseEntity<?>> naiveLogin(@RequestBody LoginRequest request, ServerWebExchange exchange) {
		return Mono.just(handle(request, exchange, this.naiveResolver, this.naiveLimiter));
	}

	private ResponseEntity<?> handle(LoginRequest request, ServerWebExchange exchange, ClientIpResolver ipResolver,
			FixedWindowRateLimiter rateLimiter) {
		String clientIp = ipResolver.resolve(exchange.getRequest().getRemoteAddress(),
				exchange.getRequest().getHeaders());
		var decision = rateLimiter.tryAcquire("ip:" + clientIp);

		HttpHeaders headers = new HttpHeaders();
		headers.set("RateLimit-Policy",
				"\"" + POLICY + "\";q=" + decision.limit() + ";w=" + rateLimiter.window().toSeconds());
		headers.set("RateLimit", "\"" + POLICY + "\";r=" + decision.remaining() + ";t=" + decision.resetSeconds());
		// Shown only so the demo makes the bucket key visible. Never echo this in production.
		headers.set("X-Demo-Rate-Limit-Key", "ip:" + clientIp);

		if (!decision.allowed()) {
			headers.set(HttpHeaders.RETRY_AFTER, Long.toString(decision.resetSeconds()));
			return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.headers(headers)
				.body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts"));
		}
		boolean validCredentials = request.username() != null && request.password() != null
				&& request.password().equals(USERS.get(request.username()));
		if (!validCredentials) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.headers(headers)
				.body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid username or password"));
		}
		return ResponseEntity.ok()
			.headers(headers)
			.body(Map.of("accessToken", this.tokens.issue("user-a").value(), "tokenType", "Bearer"));
	}

	@Override
	public Mono<Void> reset() {
		return Mono.fromRunnable(() -> {
			this.limiter.clear();
			this.naiveLimiter.clear();
		});
	}
}
