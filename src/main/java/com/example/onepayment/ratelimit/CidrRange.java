package com.example.onepayment.ratelimit;

import java.net.InetAddress;

/** An address range such as {@code 10.0.0.0/8} or a single address. */
public record CidrRange(byte[] network, int prefixLength) {

	public static CidrRange parse(String cidr) {
		String[] parts = cidr.trim().split("/", 2);
		byte[] address = InetAddress.ofLiteral(parts[0]).getAddress();
		int prefix = (parts.length == 2) ? Integer.parseInt(parts[1]) : address.length * 8;
		return new CidrRange(address, prefix);
	}

	public boolean contains(InetAddress candidate) {
		byte[] bytes = candidate.getAddress();
		if (bytes.length != this.network.length) {
			return false;
		}
		int fullBytes = this.prefixLength / 8;
		for (int i = 0; i < fullBytes; i++) {
			if (bytes[i] != this.network[i]) {
				return false;
			}
		}
		int remainingBits = this.prefixLength % 8;
		if (remainingBits == 0) {
			return true;
		}
		int mask = (0xFF << (8 - remainingBits)) & 0xFF;
		return (bytes[fullBytes] & mask) == (this.network[fullBytes] & mask);
	}
}
