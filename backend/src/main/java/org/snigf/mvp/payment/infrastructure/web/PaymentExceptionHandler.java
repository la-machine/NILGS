// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.payment.infrastructure.web;

import org.snigf.mvp.payment.application.PaymentRefused;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Errors of the payment endpoints as RFC 9457 problems with stable codes (ADR-011): 422 invalid,
 * 404 not found, 409 conflict, 403 forbidden, 503 provider or key custody unavailable, 401 a
 * callback that is not authentic (its code says why: {@code SIGNATURE_INVALID} ...).
 */
@RestControllerAdvice(assignableTypes = {PaymentController.class, CallbackController.class, ReconciliationController.class})
class PaymentExceptionHandler {

    @ExceptionHandler(PaymentRefused.class)
    ResponseEntity<ProblemDetail> refused(PaymentRefused e) {
        HttpStatus status = switch (e.kind()) {
            case INVALID -> HttpStatus.UNPROCESSABLE_CONTENT;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case UNAUTHENTIC -> HttpStatus.UNAUTHORIZED;
        };
        return ResponseEntity.status(status).body(body(status, e.code()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            HttpMediaTypeNotSupportedException.class})
    ResponseEntity<ProblemDetail> unreadable() {
        return ResponseEntity.unprocessableContent().body(body(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED"));
    }

    private static ProblemDetail body(HttpStatus status, String code) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setProperty("code", code);
        return problem;
    }
}
