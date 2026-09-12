package com.wallet.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.filter.BearerAuthFilter;
import com.wallet.filter.CorrelationIdFilter;
import com.wallet.filter.RequestLoggingFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers servlet filters in the correct execution order.
 *
 * Execution order (lower order = runs first):
 *   1. CorrelationIdFilter  — sets correlation_id in MDC (must run first so all subsequent logs have it)
 *   2. BearerAuthFilter     — sets user_id in MDC (runs after correlation so 401 logs have correlation_id)
 *   3. RequestLoggingFilter — logs the completed request (runs last, after auth, to capture final status)
 */
@Configuration
public class WebConfig {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new CorrelationIdFilter());
        bean.addUrlPatterns("/*");
        bean.setOrder(1);
        bean.setName("correlationIdFilter");
        return bean;
    }

    @Bean
    public FilterRegistrationBean<BearerAuthFilter> bearerAuthFilter(ObjectMapper objectMapper) {
        FilterRegistrationBean<BearerAuthFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new BearerAuthFilter(objectMapper));
        bean.addUrlPatterns("/*");
        bean.setOrder(2);
        bean.setName("bearerAuthFilter");
        return bean;
    }

    @Bean
    public FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilter() {
        FilterRegistrationBean<RequestLoggingFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new RequestLoggingFilter());
        bean.addUrlPatterns("/*");
        bean.setOrder(3);
        bean.setName("requestLoggingFilter");
        return bean;
    }
}
