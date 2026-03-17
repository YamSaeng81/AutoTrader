package com.cryptoautotrader.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// CORS는 SecurityConfig.corsConfigurationSource()에서 관리합니다.
// Spring Security가 있을 때 WebMvcConfigurer CORS보다 Security CORS가 먼저 적용되므로
// 중복 설정을 제거했습니다.
@Configuration
public class WebConfig implements WebMvcConfigurer {
}
