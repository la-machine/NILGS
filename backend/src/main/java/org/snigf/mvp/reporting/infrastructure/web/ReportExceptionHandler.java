// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.reporting.infrastructure.web;

import org.snigf.mvp.reporting.application.ReportRefused;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Errors of the report endpoints as RFC 9457 problems with stable codes (ADR-011):
 * {@code STATE_NOT_LISTED}, {@code PAGE_OUT_OF_RANGE}, {@code VALIDATION_FAILED} (422).
 */
@RestControllerAdvice(assignableTypes = WorkloadController.class)
class ReportExceptionHandler {

    @ExceptionHandler(ReportRefused.class)
    ResponseEntity<ProblemDetail> refused(ReportRefused e) {
        return ResponseEntity.unprocessableContent().body(body(e.code()));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ProblemDetail> unreadable() {
        return ResponseEntity.unprocessableContent().body(body("VALIDATION_FAILED"));
    }

    private static ProblemDetail body(String code) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        problem.setProperty("code", code);
        return problem;
    }
}
