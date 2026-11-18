package com.chargeflow.api;

import com.chargeflow.service.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;

/** Maps failures to a uniform JSON body: {status, error, details[], timestamp}. */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ApiError(int status, String error, List<String> details, String timestamp) { }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiError> validation(ValidationException e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), e.details());
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> badArg(Exception e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage(), List.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        String msg = e.getMostSpecificCause() == null ? e.getMessage() : e.getMostSpecificCause().getMessage();
        if (msg != null && msg.length() > 300) msg = msg.substring(0, 300) + "...";
        return body(HttpStatus.BAD_REQUEST, "Malformed JSON request", List.of(String.valueOf(msg)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        return body(HttpStatus.BAD_REQUEST, "Invalid request",
                e.getBindingResult().getFieldErrors().stream().map(f -> f.getField() + ": " + f.getDefaultMessage()).toList());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> other(Exception e) {
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error: " + e.getClass().getSimpleName(),
                List.of(String.valueOf(e.getMessage())));
    }

    private static ResponseEntity<ApiError> body(HttpStatus s, String msg, List<String> details) {
        return ResponseEntity.status(s).body(new ApiError(s.value(), msg, details, Instant.now().toString()));
    }
}
