package com.example.onepayment.refunds;

import com.example.onepayment.auth.CallerIdentity;
import com.example.onepayment.common.ApiException;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Failure #5: optimistic concurrency with {@code ETag} and {@code If-Match}, enforced all the
 * way down to the SQL statement.
 */
@RestController
class RefundController {

	private final RefundRepository refunds;

	RefundController(RefundRepository refunds) {
		this.refunds = refunds;
	}

	@GetMapping("/api/refunds/{id}")
	Mono<ResponseEntity<Refund>> get(@PathVariable String id) {
		return this.refunds.findById(id)
			.map(refund -> ResponseEntity.ok().eTag(ETags.of(refund)).body(refund))
			.switchIfEmpty(Mono.error(() -> notFound(id)));
	}

	@PatchMapping("/api/refunds/{id}")
	Mono<ResponseEntity<Refund>> updateStatus(@RequestAttribute(CallerIdentity.ATTRIBUTE) CallerIdentity caller,
			@PathVariable String id, @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody RefundStatusUpdate update) {
		if (ifMatch == null) {
			// Without this, a client that "forgets" If-Match silently overwrites everyone else.
			return Mono.error(new ApiException(HttpStatus.PRECONDITION_REQUIRED, "Precondition Required",
					"Send If-Match with the ETag you last read"));
		}
		ETags.Precondition precondition = ETags.parseIfMatch(ifMatch);
		return this.refunds.findById(id).switchIfEmpty(Mono.error(() -> notFound(id))).flatMap(current -> {
			if (!precondition.matches(current.version())) {
				return Mono.error(preconditionFailed(current));
			}
			if (!current.status().canTransitionTo(update.status())) {
				return Mono.error(new ApiException(HttpStatus.CONFLICT, "Invalid transition",
						"Refund is already " + current.status()));
			}
			// Another request may have committed since our SELECT; the WHERE version = ? catches it.
			return this.refunds.updateStatusIfVersion(id, update.status(), current.version(), caller.userId())
				.flatMap(rows -> this.refunds.findById(id)
					.flatMap(latest -> (rows == 1) ? Mono.just(ResponseEntity.ok().eTag(ETags.of(latest)).body(latest))
							: Mono.error(preconditionFailed(latest))));
		});
	}

	private static ApiException preconditionFailed(Refund current) {
		return new ApiException(HttpStatus.PRECONDITION_FAILED, "Precondition Failed",
				"The refund was modified by someone else. Fetch it again before deciding.")
			.header(HttpHeaders.ETAG, ETags.of(current));
	}

	private static ApiException notFound(String id) {
		return new ApiException(HttpStatus.NOT_FOUND, "Not Found", "Refund " + id + " was not found");
	}
}
