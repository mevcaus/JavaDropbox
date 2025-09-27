package com.javadropbox.javadropbox.config;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class FileUploadExceptionAdvice {

    // Fallback for other multipart errors
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleMaxSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("message", "File too large! Maximum upload size exceeded."));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, String>> handleMultipart(MultipartException ex) {
        if (isSizeLimitException(ex)) {
            return ResponseEntity
                    .status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("message", "File too large! Maximum upload size exceeded."));
        }
        return ResponseEntity
                .badRequest()
                .body(Map.of("message", "Invalid multipart request."));
    }

    private boolean isSizeLimitException(Throwable ex) {
        // Walk the cause chain and check for known size-limit exception types
        Throwable cur = ex;
        while (cur != null) {
            String name = cur.getClass().getName();
            if (name.endsWith("MaxUploadSizeExceededException")
                    || name.endsWith("SizeLimitExceededException")
                    || name.endsWith("FileSizeLimitExceededException")) {
                return true;
            }
            cur = cur.getCause();
        }
        // some containers throw IllegalStateException on size overflow
        String msg = ex.getMessage();
        return msg != null && msg.toLowerCase().contains("size");
    }
}