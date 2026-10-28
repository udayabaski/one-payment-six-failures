package com.example.onepayment.common;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * An error that maps to an RFC 9457 problem response, optionally with extra headers
 * such as {@code Retry-After} or {@code WWW-Authenticate}.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final String title;

	private final HttpHeaders headers = new HttpHeaders();

	public ApiException(HttpStatus status, String title, String detail) {
		super(detail);
		this.status = status;
		this.title = title;
	}

	public ApiException header(String name, String value) {
		this.headers.set(name, value);
		return this;
	}

	public ApiException retryAfter(Duration delay) {
		return header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, delay.toSeconds())));
	}

	public HttpStatus status() {
		return this.status;
	}

	public String title() {
		return this.title;
	}

	public HttpHeaders headers() {
		return this.headers;
	}
}
