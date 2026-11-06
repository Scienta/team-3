package no.scienta.cfp;

import java.util.Map;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Writes the client errors' JSON bodies inside the request, instead of leaving them to Spring Boot's error dispatch,
 * which runs after {@link RequestLoggingFilter} has logged the request with an empty response body. The body is built
 * by Boot's own {@link ErrorAttributes}, so it's the same {timestamp, status, error, message, path} (plus
 * {@code errors} for validation failures) that clients got before.
 */
@RestControllerAdvice
public class ApiErrorHandler {

    private final ErrorAttributes errorAttributes;

    public ApiErrorHandler(ErrorAttributes errorAttributes) {
        this.errorAttributes = errorAttributes;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> statusException(ResponseStatusException e, HttpServletRequest request) {
        return body(e.getStatusCode(), e.getReason(), e, request);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception e, HttpServletRequest request) {
        // Boot sends these without a message; the validation details go in "errors".
        return body(HttpStatus.BAD_REQUEST, "", e, request);
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatusCode status, String message, Exception e,
                                                     HttpServletRequest request) {
        // The attributes the servlet container sets for an error dispatch, which ErrorAttributes reads.
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status.value());
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, request.getRequestURI());
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, message == null ? "" : message);
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, e);
        Map<String, Object> body = errorAttributes.getErrorAttributes(new ServletWebRequest(request),
                // defaults() is status, error and path (opt-in since Boot 3); plus message and errors as before
                ErrorAttributeOptions.defaults().including(ErrorAttributeOptions.Include.MESSAGE,
                        ErrorAttributeOptions.Include.BINDING_ERRORS));
        return ResponseEntity.status(status).body(body);
    }
}
