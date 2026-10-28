package com.example.onepayment.simulation;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.example.onepayment.provider.FakePaymentProvider;
import com.example.onepayment.provider.ProviderCharge;
import com.example.onepayment.retry.RetryStormSimulator;
import com.example.onepayment.retry.RetryStormSimulator.Scenario;
import com.example.onepayment.retry.RetryStormSimulator.Strategy;
import com.example.onepayment.tracing.InMemorySpanExporter;
import com.example.onepayment.tracing.TracingSettings;
import reactor.core.publisher.Mono;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Knobs for the demo scripts: inject faults, inspect the provider, view traces.
 * Obviously not something a real payment API would expose.
 */
@RestController
@RequestMapping("/simulation")
class SimulationController {

	private final SimulationReset reset;

	private final FakePaymentProvider provider;

	private final FaultInjector faults;

	private final TracingSettings tracingSettings;

	private final InMemorySpanExporter spans;

	SimulationController(SimulationReset reset, FakePaymentProvider provider, FaultInjector faults,
			TracingSettings tracingSettings, InMemorySpanExporter spans) {
		this.reset = reset;
		this.provider = provider;
		this.faults = faults;
		this.tracingSettings = tracingSettings;
		this.spans = spans;
	}

	record ProviderSettings(long latencyMs, boolean unavailable) {
	}

	record TracingToggle(boolean propagateOverHttp, boolean propagateOverMessaging) {
	}

	@PostMapping("/reset")
	Mono<String> reset() {
		return this.reset.resetAll().thenReturn("reset\n");
	}

	@GetMapping("/provider/charges")
	List<ProviderCharge> charges() {
		return this.provider.charges();
	}

	@PutMapping("/provider")
	ProviderSettings configureProvider(@RequestBody ProviderSettings settings) {
		this.provider.configure(Duration.ofMillis(settings.latencyMs()), settings.unavailable());
		return settings;
	}

	@PostMapping("/faults/crash-after-provider-charge")
	String armCrash(@RequestParam(defaultValue = "1") int times) {
		this.faults.armCrashAfterProviderCharge(times);
		return "armed: the next " + times + " payment(s) crash after the provider charges the card\n";
	}

	@PutMapping("/tracing")
	TracingToggle configureTracing(@RequestBody TracingToggle toggle) {
		this.tracingSettings.configure(toggle.propagateOverHttp(), toggle.propagateOverMessaging());
		return toggle;
	}

	@GetMapping(path = "/traces/{traceId}", produces = MediaType.TEXT_PLAIN_VALUE)
	String trace(@PathVariable String traceId) {
		return this.spans.render(traceId);
	}

	@GetMapping(path = "/retry-storm", produces = MediaType.TEXT_PLAIN_VALUE)
	String retryStorm(@RequestParam(defaultValue = "10000") int clients,
			@RequestParam(required = false) Strategy strategy) {
		Scenario defaults = Scenario.defaults();
		Scenario scenario = new Scenario(clients, defaults.outage(), defaults.capacityPerSecond(),
				defaults.retryAfter(), defaults.maxAttempts(), defaults.deadline(), defaults.seed());
		List<Strategy> strategies = (strategy != null) ? List.of(strategy) : Arrays.asList(Strategy.values());
		return strategies.stream()
			.map(s -> RetryStormSimulator.render(RetryStormSimulator.run(s, scenario)))
			.collect(Collectors.joining(System.lineSeparator()));
	}
}
