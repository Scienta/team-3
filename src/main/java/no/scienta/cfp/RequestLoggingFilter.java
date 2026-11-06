package no.scienta.cfp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

// The base app logs no requests, so the harness's traffic is invisible in Cockpit. This filter logs
// one line per request: method, path (with query), status and duration, plus truncated request and
// response bodies, which is the main way to see what the checks actually send and how we answer.
// /health is skipped: the deploy pipeline polls it constantly and it would drown out the real traffic.
// Error bodies show up because ApiErrorHandler writes them inside this request, not in a later error dispatch.
// Email addresses are masked to their domain, since Cockpit logs aren't the place for personal data.
@Component
@Order(Integer.MIN_VALUE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("requests");
    private static final int MAX_BODY = 2048;
    private static final Pattern EMAIL_LOCAL_PART = Pattern.compile("[^\\s\"@,;:<>()\\[\\]]+@(?=[^\\s\"@]+\\.)");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/health".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            long millis = (System.nanoTime() - start) / 1_000_000;
            String query = req.getQueryString() == null ? "" : "?" + req.getQueryString();
            log.info("{} {}{} -> {} ({} ms) req={} res={}",
                    req.getMethod(), req.getRequestURI(), query, res.getStatus(), millis,
                    body(req.getContentAsByteArray()), body(res.getContentAsByteArray()));
            // copyBodyToResponse must run, else the cached response body is never flushed to the client.
            res.copyBodyToResponse();
        }
    }

    private static String body(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "-";
        }
        int len = Math.min(bytes.length, MAX_BODY);
        String text = new String(bytes, 0, len, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
        text = EMAIL_LOCAL_PART.matcher(text).replaceAll("***@");
        return len < bytes.length ? text + "…(" + bytes.length + "B)" : text;
    }
}
