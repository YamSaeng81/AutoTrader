package com.cryptoautotrader.api.support.sqltrace;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * {@link RecordingDataSource} 로 애플리케이션의 {@code DataSource} 를 감싼다 — <b>테스트 전용</b>.
 *
 * <p>🔴 <b>기본은 꺼져 있다.</b> {@link SqlTrace#start()} 를 부르기 전까지 래퍼는 기록하지 않고
 * 위임만 하므로, 이 설정을 가져온 테스트라도 관측이 켜지지 않으면 거동이 달라지지 않는다.
 *
 * <p>⚠️ 래퍼는 {@code HikariDataSource} 타입을 감추므로 Hikari 전용 메트릭이 테스트에서 빠질 수
 * 있다. 시험 대상과 무관하며, 운영에는 적용되지 않는다(테스트 소스 트리에만 있다).
 */
@TestConfiguration
public class SqlTraceConfig {

    @Bean
    static BeanPostProcessor sqlTraceDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && !(bean instanceof RecordingDataSource)) {
                    return new RecordingDataSource(ds);
                }
                return bean;
            }
        };
    }
}
