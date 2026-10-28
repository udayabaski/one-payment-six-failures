package com.example.onepayment.ratelimit;

import java.net.InetSocketAddress;

import org.springframework.http.HttpHeaders;

/**
 * Failure #4, the tempting version: "the first X-Forwarded-For entry is the client".
 * Whoever sends the request controls that value. DO NOT COPY THIS.
 */
public class NaiveClientIpResolver implements ClientIpResolver {

	@Override
	public String resolve(InetSocketAddress peer, HttpHeaders headers) {
		String forwardedFor = headers.getFirst("X-Forwarded-For");
		if (forwardedFor != null && !forwardedFor.isBlank()) {
			return forwardedFor.split(",")[0].trim();
		}
		return ClientIpResolver.peerAddress(peer);
	}
}
