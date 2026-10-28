package com.example.onepayment.ratelimit;

import java.net.InetSocketAddress;

import org.springframework.http.HttpHeaders;

/** Decides which client address a request is charged to. */
public interface ClientIpResolver {

	String resolve(InetSocketAddress peer, HttpHeaders headers);

	static String peerAddress(InetSocketAddress peer) {
		return (peer != null && peer.getAddress() != null) ? peer.getAddress().getHostAddress() : "unknown";
	}
}
