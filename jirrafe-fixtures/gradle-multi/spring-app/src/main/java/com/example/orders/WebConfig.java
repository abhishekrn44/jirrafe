package com.example.orders;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the API key check on every /orders route. */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final ApiKeyInterceptor apiKey;

    public WebConfig(ApiKeyInterceptor apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKey).addPathPatterns("/orders/**");
    }
}
