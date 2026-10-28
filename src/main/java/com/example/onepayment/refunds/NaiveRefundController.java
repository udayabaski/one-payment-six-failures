package com.example.onepayment.refunds;

import java.time.Duration;

import com.example.onepayment.auth.CallerIdentity;
import com.example.onepayment.common.ApiException;
import com.example.onepayment.config.PaymentsProperties;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Failure #5, two tempting versions. DO NOT COPY THESE. */
@RestController
class NaiveRefundController {

	private final RefundRepository refunds;

	private final Duration raceWindow;

	NaiveRefundController(RefundRepository refunds, PaymentsProperties properties) {
		this.refunds = refunds;
		this.raceWindow = properties.refunds().naiveRaceWindow();
	}

	/** Ignores If-Match entirely: last write wins. */
	@PatchMapping("/naive/f5/refunds/{id}")
	Mono<ResponseEntity<Refund>> blindOverwrite(@RequestAttribute(CallerIdentity.ATTRIBUTE) CallerIdentity caller,
			@PathVariable String id, @RequestBody RefundStatusUpdate update) {
		return this.refunds.updateStatusBlindly(id, update.status(), caller.userId())
			.then(this.refunds.findById(id))
			.map(refund -> ResponseEntity.ok().eTag(ETags.of(refund)).body(refund));
	}

	/**
	 * Honours If-Match in Java, then writes without a version predicate. Correct in a demo
	 * with one user, wrong as soon as two requests overlap.
	 */
	@PatchMapping("/naive/f5/refunds/{id}/check-then-act")
	Mono<ResponseEntity<Refund>> checkThenAct(@RequestAttribute(CallerIdentity.ATTRIBUTE) CallerIdentity caller,
			@PathVariable String id, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
			@RequestBody RefundStatusUpdate update) {
		return this.refunds.findById(id)
			.flatMap(current -> {
				if (!ETags.parseIfMatch(ifMatch).matches(current.version())) {
					return Mono.error(new ApiException(HttpStatus.PRECONDITION_FAILED, "Precondition Failed",
							"Stale ETag"));
				}
				// SELECT version ... IF version == 7 ... (another request runs here) ... UPDATE
				return Mono.delay(this.raceWindow)
					.then(this.refunds.updateStatusBlindly(id, update.status(), caller.userId()));
			})
			.then(this.refunds.findById(id))
			.map(refund -> ResponseEntity.ok().eTag(ETags.of(refund)).body(refund));
	}
}
