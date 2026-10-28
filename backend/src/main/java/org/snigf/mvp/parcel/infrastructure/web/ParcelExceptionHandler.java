// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.parcel.infrastructure.web;

import java.util.List;

import org.snigf.mvp.parcel.application.ParcelRefused;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Errors of the parcel endpoints as RFC 9457 problems with stable codes (ADR-011). A refused
 * geometry carries PostGIS's reason in {@code reason} (MVP-01: "rejected with the
 * ST_IsValidReason text"); nothing else from the server is ever echoed.
 */
@RestControllerAdvice(assignableTypes = ParcelController.class)
class ParcelExceptionHandler {

    @ExceptionHandler(ParcelRefused.class)
    ResponseEntity<ProblemDetail> refused(ParcelRefused e) {
        HttpStatus status = switch (e.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        ProblemDetail problem = body(status, e.code());
        if (e.detail() != null) {
            problem.setProperty("reason", e.detail());
        }
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e) {
        ProblemDetail problem = body(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED");
        List<String> fields = e.getBindingResult().getFieldErrors().stream().map(f -> f.getField()).distinct().toList();
        problem.setProperty("fields", fields);
        return ResponseEntity.unprocessableContent().body(problem);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> unreadable() {
        return ResponseEntity.unprocessableContent().body(body(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED"));
    }

    private static ProblemDetail body(HttpStatus status, String code) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setProperty("code", code);
        return problem;
    }
}
