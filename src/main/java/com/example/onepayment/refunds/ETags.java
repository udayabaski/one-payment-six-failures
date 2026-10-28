package com.example.onepayment.refunds;

import java.util.OptionalLong;

/**
 * Maps refund row versions to entity tags. An ETag identifies a version of the resource's
 * representation; here we define it as "one ETag per row version" and document that mapping.
 */
final class ETags {

	private static final String PREFIX = "\"refund-v";

	private ETags() {
	}

	static String of(Refund refund) {
		return PREFIX + refund.version() + "\"";
	}

	/** What an If-Match header asks for. */
	sealed interface Precondition permits AnyVersion, Versions {

		boolean matches(long version);
	}

	record AnyVersion() implements Precondition {

		@Override
		public boolean matches(long version) {
			return true;
		}
	}

	record Versions(long[] versions) implements Precondition {

		@Override
		public boolean matches(long version) {
			for (long candidate : this.versions) {
				if (candidate == version) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * If-Match uses strong comparison, so weak tags ({@code W/"..."}) never match.
	 */
	static Precondition parseIfMatch(String header) {
		if (header.trim().equals("*")) {
			return new AnyVersion();
		}
		return new Versions(java.util.Arrays.stream(header.split(","))
			.map(String::trim)
			.map(ETags::version)
			.filter(OptionalLong::isPresent)
			.mapToLong(OptionalLong::getAsLong)
			.toArray());
	}

	private static OptionalLong version(String tag) {
		if (!tag.startsWith(PREFIX) || !tag.endsWith("\"")) {
			return OptionalLong.empty();
		}
		try {
			return OptionalLong.of(Long.parseLong(tag.substring(PREFIX.length(), tag.length() - 1)));
		}
		catch (NumberFormatException ex) {
			return OptionalLong.empty();
		}
	}
}
