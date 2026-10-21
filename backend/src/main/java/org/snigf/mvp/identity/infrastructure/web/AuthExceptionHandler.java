// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.identity.infrastructure.web;

import java.util.List;

import org.snigf.mvp.identity.application.RegistrationRejected;
import org.snigf.mvp.identity.domain.PasswordHasher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Errors of the authentication endpoints as RFC 9457 problems with stable codes (ADR-011).
 * Validation errors name the fields, never echo their values.
 */
@RestControllerAdvice(assignableTypes = AuthController.class)
class AuthExceptionHandler {

    /** Any failed login: unknown account, wrong password, locked, disabled. */
    static class AuthenticationFailed extends RuntimeException {
        AuthenticationFailed() {
            super("authentication failed");
        }
    }

    @ExceptionHandler(AuthenticationFailed.class)
    ResponseEntity<ProblemDetail> failed() {
        return problem(HttpStatus.UNAUTHORIZED, "AUTH_FAILED");
    }

    @ExceptionHandler(AuthRateLimiter.Limited.class)
    ResponseEntity<ProblemDetail> limited(AuthRateLimiter.Limited e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(e.retryAfterSeconds()))
                .body(body(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED"));
    }

    @ExceptionHandler(PasswordHasher.Busy.class)
    ResponseEntity<ProblemDetail> busy() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(body(HttpStatus.SERVICE_UNAVAILABLE, "BUSY"));
    }

    @ExceptionHandler(RegistrationRejected.class)
    ResponseEntity<ProblemDetail> rejected(RegistrationRejected e) {
        ProblemDetail body = body(HttpStatus.UNPROCESSABLE_CONTENT, e.onlyPassword() ? "PASSWORD_WEAK" : "VALIDATION_FAILED");
        body.setProperty("violations", e.codes());
        return ResponseEntity.unprocessableContent().body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e) {
        ProblemDetail body = body(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED");
        List<String> fields = e.getBindingResult().getFieldErrors().stream().map(f -> f.getField()).distinct().toList();
        body.setProperty("fields", fields);
        return ResponseEntity.unprocessableContent().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable() {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(body(status, code));
    }

    private static ProblemDetail body(HttpStatus status, String code) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setProperty("code", code);
        return problem;
    }
}
