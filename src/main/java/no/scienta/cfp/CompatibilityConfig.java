package no.scienta.cfp;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.filter.UrlHandlerFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Keeps what clients see the same as on Spring Boot 2.7 / Spring Framework 5 / Jackson 2, where Boot 4 changed it.
 */
@Configuration
public class CompatibilityConfig implements WebMvcConfigurer {

    // Jackson 2's StdDateFormat: "2026-11-06T12:28:22.489+00:00". Jackson 3 writes "...489Z".
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx").withZone(ZoneOffset.UTC);

    /**
     * Dates as Jackson 2 wrote them: java.util.Date (TalkDto.submittedAt, the error bodies' timestamp) as
     * "2026-11-06T12:28:22.489+00:00", java.sql.Date (TalkDto.submittedOn) as "2026-11-06". Jackson 3 writes
     * "...489Z" and a full timestamp for java.sql.Date. This serializer also gets java.sql.Date (a subclass).
     */
    @Bean
    public SimpleModule legacyDateModule() {
        SimpleModule module = new SimpleModule("legacy-dates");
        module.addSerializer(Date.class, new ValueSerializer<Date>() {
            @Override
            public void serialize(Date value, JsonGenerator gen, SerializationContext ctxt) {
                if (value instanceof java.sql.Date) {
                    gen.writeString(value.toString());  // yyyy-MM-dd; toInstant() isn't supported on java.sql.Date
                } else {
                    gen.writeString(TIMESTAMP.format(value.toInstant()));
                }
            }
        });
        return module;
    }

    /**
     * Spring Framework 5 matched "/talks/1/" like "/talks/1"; 6+ doesn't. Strip the trailing slash before the
     * request reaches the controllers. Runs after RequestLoggingFilter, so the log still shows the path as sent.
     */
    @Bean
    public FilterRegistrationBean<UrlHandlerFilter> trailingSlashFilter() {
        FilterRegistrationBean<UrlHandlerFilter> bean = new FilterRegistrationBean<>(
                UrlHandlerFilter.trailingSlashHandler("/**").wrapRequest().build());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return bean;
    }

    /**
     * "Content-Type: application/json;charset=UTF-8" on every JSON response, errors included. Boot 2.7 got the
     * charset from the forced CharacterEncodingFilter, but Spring 7 resets the response before an exception
     * handler or the error page writes it, which drops the charset; the converter adds it back.
     */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof JacksonJsonHttpMessageConverter) {
                ((JacksonJsonHttpMessageConverter) converter).setDefaultCharset(StandardCharsets.UTF_8);
            }
        }
    }
}
