package com.example.onepayment.provider;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import com.example.onepayment.config.PaymentsProperties;
import com.example.onepayment.tracing.SpanKind;
import com.example.onepayment.tracing.Tracer;
import com.example.onepayment.tracing.TracingSettings;
import reactor.core.publisher.Mono;

import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

/** Our side of the provider integration: an HTTP client with tracing and error mapping. */
@Component
public class PaymentProviderClient implements ApplicationListener<WebServerInitializedEvent> {

	private static final String SERVICE = "payments-api";

	private final WebClient.Builder builder;

	private final Duration timeout;

	private final Tracer tracer;

	private final TracingSettings tracingSettings;

	private volatile WebClient webClient;

	public PaymentProviderClient(WebClient.Builder builder, PaymentsProperties properties, Tracer tracer,
			TracingSettings tracingSettings) {
		this.builder = builder;
		this.timeout = properties.provider().timeout();
		this.tracer = tracer;
		this.tracingSettings = tracingSettings;
		String baseUrl = properties.provider().baseUrl();
		if (baseUrl != null && !baseUrl.isBlank()) {
			this.webClient = builder.clone().baseUrl(baseUrl).build();
		}
	}

	@Override
	public void onApplicationEvent(WebServerInitializedEvent event) {
		if (this.webClient == null) {
			this.webClient = this.builder.clone()
				.baseUrl("http://localhost:" + event.getWebServer().getPort())
				.build();
		}
	}

	/**
	 * @param idempotencyKey the provider-level idempotency key, or {@code null} to send none
	 */
	public Mono<ProviderCharge> charge(String idempotencyKey, ProviderChargeRequest request) {
		Mono<ProviderCharge> call = Mono.deferContextual(context -> this.webClient.post()
			.uri("/provider/v1/charges")
			.headers(headers -> {
				if (idempotencyKey != null) {
					headers.set("Idempotency-Key", idempotencyKey);
				}
				if (this.tracingSettings.propagateOverHttp()) {
					Tracer.inject(context, headers::set);
				}
			})
			.bodyValue(request)
			.retrieve()
			.onStatus(status -> status.value() == HttpStatus.SERVICE_UNAVAILABLE.value(),
					response -> Mono.error(new ProviderUnavailableException()))
			.bodyToMono(ProviderCharge.class)
			.timeout(this.timeout)
			.onErrorMap(error -> error instanceof TimeoutException || error instanceof WebClientRequestException,
					ProviderOutcomeUnknownException::new));
		return this.tracer.inSpan(SERVICE, "payment-provider POST /v1/charges", SpanKind.CLIENT, call);
	}

	/** Looks a charge up by the reference we persisted before calling the provider. */
	public Mono<ProviderCharge> findByReference(String reference) {
		Mono<ProviderCharge> call = Mono.deferContextual(context -> this.webClient.get()
			.uri(uri -> uri.path("/provider/v1/charges").queryParam("reference", reference).build())
			.headers(headers -> {
				if (this.tracingSettings.propagateOverHttp()) {
					Tracer.inject(context, headers::set);
				}
			})
			.exchangeToMono(response -> {
				if (response.statusCode().value() == HttpStatus.NOT_FOUND.value()) {
					return response.releaseBody().then(Mono.<ProviderCharge>empty());
				}
				return response.statusCode().is2xxSuccessful() ? response.bodyToMono(ProviderCharge.class)
						: response.createError();
			})
			.timeout(this.timeout));
		return this.tracer.inSpan(SERVICE, "payment-provider GET /v1/charges", SpanKind.CLIENT, call);
	}
}
