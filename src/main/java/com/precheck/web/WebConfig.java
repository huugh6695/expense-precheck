package com.precheck.web;

import com.precheck.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册验签拦截器。
 *
 * <p>范围：所有 {@code /api/**}，只放过一个查开关状态的接口——
 * 前端得先知道"现在到底验不验签"，才能决定页面上要不要挂"台账不可信"的横幅。
 * 那个接口本身不返回任何业务数据，公开它不会泄露什么。
 *
 * <p>静态页面（/index.html）不拦：它只是一个壳，真正的数据都在 /api 后面。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/status");
    }
}
