package com.akhester.auditui.activity;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ActivityWebConfig implements WebMvcConfigurer {

    private final ActivityInterceptor interceptor;

    public ActivityWebConfig(ActivityInterceptor interceptor) { this.interceptor = interceptor; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).excludePathPatterns("/css/**", "/js/**", "/fonts/**", "/demo/**", "/error", "/actuator/**");
    }
}
