package com.bitman.justbuy.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SPA (Single Page Application) 라우팅 설정.
 * React Router 경로를 index.html로 포워딩하여 클라이언트 사이드 라우팅 지원.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 1단계 경로: /login, /register, /landing, /supply, /my, /admin, /subscribe
        registry.addViewController("/{path:[^\\.]*}")
                .setViewName("forward:/index.html");
        // 2단계 경로 (혹시 추가될 경우)
        registry.addViewController("/{path:[^\\.]*}/{subpath:[^\\.]*}")
                .setViewName("forward:/index.html");
    }
}
