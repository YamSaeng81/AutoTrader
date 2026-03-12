package com.cryptoautotrader.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("CryptoAutoTrader API")
                        .description("업비트 기반 암호화폐 자동매매 시스템 - 백테스팅 & 데이터 수집 API")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("CryptoAutoTrader")
                                .email("admin@cryptoautotrader.com")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("개발 서버"),
                        new Server().url("http://localhost:80").description("운영 서버 (Docker)")
                ));
    }
}
