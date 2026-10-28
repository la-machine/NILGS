// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.review.infrastructure.web;

import java.util.List;

import org.snigf.mvp.review.application.ReviewRefused;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Errors of the review endpoints as RFC 9457 problems with stable codes (ADR-011):
 * {@code GROUNDS_REQUIRED}, {@code CITATIONS_REQUIRED}, {@code FINDING_REQUIRED} (422),
 * {@code OPEN_ALERT}, {@code INVALID_TRANSITION}, {@code ALREADY_ASSIGNED}, {@code STALE_VERSION} (409),
 * {@code NOT_ASSIGNED}, {@code RECOMMENDER_CANNOT_DECIDE} (403).
 */
@RestControllerAdvice(assignableTypes = ReviewController.class)
class ReviewExceptionHandler {

    @ExceptionHandler(ReviewRefused.class)
    ResponseEntity<ProblemDetail> refused(ReviewRefused e) {
        HttpStatus status = switch (e.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.UNPROCESSABLE_CONTENT;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
        };
        return ResponseEntity.status(status).body(body(status, e.code()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e) {
        ProblemDetail problem = body(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED");
        List<String> fields = e.getBindingResult().getFieldErrors().stream().map(f -> f.getField()).distinct().toList();
        problem.setProperty("fields", fields);
        return ResponseEntity.unprocessableContent().body(problem);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<ProblemDetail> unreadable() {
        return ResponseEntity.unprocessableContent().body(body(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED"));
    }

    private static ProblemDetail body(HttpStatus status, String code) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setProperty("code", code);
        return problem;
    }
}
