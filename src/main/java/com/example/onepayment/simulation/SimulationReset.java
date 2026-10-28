package com.example.onepayment.simulation;

import java.util.List;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class SimulationReset {

	private final List<Resettable> resettables;

	SimulationReset(List<Resettable> resettables) {
		this.resettables = resettables;
	}

	public Mono<Void> resetAll() {
		return Flux.fromIterable(this.resettables).concatMap(Resettable::reset).then();
	}

	@EventListener(ApplicationReadyEvent.class)
	void seedOnStartup() {
		resetAll().block();
	}
}
