package com.example.onepayment.tracing;

import com.example.onepayment.simulation.Resettable;
import reactor.core.publisher.Mono;

import org.springframework.stereotype.Component;

/**
 * Switches that let Failure #6 show what happens when one hop forgets to propagate context.
 */
@Component
public class TracingSettings implements Resettable {

	private volatile boolean propagateOverHttp = true;

	private volatile boolean propagateOverMessaging = true;

	public boolean propagateOverHttp() {
		return this.propagateOverHttp;
	}

	public boolean propagateOverMessaging() {
		return this.propagateOverMessaging;
	}

	public void configure(boolean propagateOverHttp, boolean propagateOverMessaging) {
		this.propagateOverHttp = propagateOverHttp;
		this.propagateOverMessaging = propagateOverMessaging;
	}

	@Override
	public Mono<Void> reset() {
		return Mono.fromRunnable(() -> configure(true, true));
	}
}
