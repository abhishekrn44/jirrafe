package com.example.orders;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** The authentication mechanism: every request must carry the configured API key in the X-Api-Key header. */
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {
    private final String apiKey;

    public ApiKeyInterceptor(@Value("${orders.api-key}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!isAuthorized(request.getHeader("X-Api-Key"))) {
            response.setStatus(401);
            return false;
        }
        return true;
    }

    boolean isAuthorized(String presented) {
        return presented != null && presented.equals(apiKey);
    }
}
