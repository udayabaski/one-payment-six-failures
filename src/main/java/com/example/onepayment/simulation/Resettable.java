package com.example.onepayment.simulation;

import reactor.core.publisher.Mono;

/** Anything holding simulation state that must be wiped between scenarios. */
public interface Resettable {

	Mono<Void> reset();
}
