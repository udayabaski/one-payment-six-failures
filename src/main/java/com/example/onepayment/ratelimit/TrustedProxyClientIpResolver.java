package com.example.onepayment.ratelimit;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpHeaders;

/**
 * Failure #4: derives the client address according to an explicit proxy trust policy.
 * <ol>
 * <li>If the TCP peer is not one of our proxies, it IS the client. Headers are ignored.</li>
 * <li>Otherwise walk the forwarding chain from the right (the entries our own proxies appended)
 * and stop at the first address that is not one of ours.</li>
 * </ol>
 * Entries further left were supplied by the client and can be anything.
 */
public class TrustedProxyClientIpResolver implements ClientIpResolver {

	private final List<CidrRange> trustedProxies;

	public TrustedProxyClientIpResolver(List<CidrRange> trustedProxies) {
		this.trustedProxies = List.copyOf(trustedProxies);
	}

	@Override
	public String resolve(InetSocketAddress peer, HttpHeaders headers) {
		String peerAddress = ClientIpResolver.peerAddress(peer);
		if (peer == null || peer.getAddress() == null || !isTrusted(peer.getAddress())) {
			return peerAddress;
		}
		List<String> chain = forwardingChain(headers);
		String candidate = peerAddress;
		for (int i = chain.size() - 1; i >= 0; i--) {
			Optional<InetAddress> hop = parseLiteral(chain.get(i));
			if (hop.isEmpty()) {
				// Garbage, "unknown" or an obfuscated identifier: we can't go further back.
				break;
			}
			candidate = hop.get().getHostAddress();
			if (!isTrusted(hop.get())) {
				return candidate;
			}
		}
		return candidate;
	}

	private boolean isTrusted(InetAddress address) {
		return this.trustedProxies.stream().anyMatch(range -> range.contains(address));
	}

	/** {@code Forwarded} (RFC 7239) if present, otherwise {@code X-Forwarded-For}. */
	static List<String> forwardingChain(HttpHeaders headers) {
		List<String> chain = new ArrayList<>();
		List<String> forwarded = headers.getOrEmpty("Forwarded");
		if (!forwarded.isEmpty()) {
			for (String header : forwarded) {
				for (String element : header.split(",")) {
					for (String pair : element.split(";")) {
						String[] kv = pair.trim().split("=", 2);
						if (kv.length == 2 && kv[0].trim().equalsIgnoreCase("for")) {
							chain.add(stripPort(unquote(kv[1].trim())));
						}
					}
				}
			}
			return chain;
		}
		for (String header : headers.getOrEmpty("X-Forwarded-For")) {
			for (String entry : header.split(",")) {
				chain.add(entry.trim());
			}
		}
		return chain;
	}

	private static String unquote(String value) {
		return (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
				? value.substring(1, value.length() - 1) : value;
	}

	private static String stripPort(String node) {
		if (node.startsWith("[")) {
			int end = node.indexOf(']');
			return (end > 0) ? node.substring(1, end) : node;
		}
		int colon = node.indexOf(':');
		return (colon > 0 && colon == node.lastIndexOf(':')) ? node.substring(0, colon) : node;
	}

	/** Literal IPs only: never trigger a DNS lookup for attacker-supplied text. */
	private static Optional<InetAddress> parseLiteral(String value) {
		try {
			return Optional.of(InetAddress.ofLiteral(value));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}
}
