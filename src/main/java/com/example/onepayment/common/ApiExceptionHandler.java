package com.example.onepayment.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> handle(ApiException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
		problem.setTitle(ex.title());
		return ResponseEntity.status(ex.status()).headers(ex.headers()).body(problem);
	}

	@ExceptionHandler(SimulatedCrashException.class)
	ResponseEntity<ProblemDetail> handle(SimulatedCrashException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
		problem.setTitle("Simulated crash");
		return ResponseEntity.internalServerError().body(problem);
	}
}
