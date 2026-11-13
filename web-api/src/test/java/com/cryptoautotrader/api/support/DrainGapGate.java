package com.cryptoautotrader.api.support;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 진단 전용 — 옛 실행기 대기 조건의 공백을 <b>결정적으로</b> 여는 시험 지원 (총괄 v1.164 §3-1·2, 2026-11-13).
 *
 * <p>환경 변수 {@code DRAIN_GAP_GATE_MS} 가 있을 때만 켜진다. 없으면 이 공장은 {@code null} 을 돌려주므로 컨텍스트·캐시 키·실행기는
 * 종전과 같다. 켜지면 {@code DRAIN_GAP_GATE_BEANS}(기본 {@code orderExecutor,marketDataExecutor}) 실행기를 <b>같은 설정</b>(코어·최대·
 * 대기열 용량·이름·거절 정책)의 실행기로 바꾸되, 작업자가 대기열에서 작업을 <b>꺼낸 뒤 실행 표시(lock) 전</b>에 지정한 밀리초만큼 머문다.
 * 그동안 "대기열 비었음·active 0·taskCount == completedTaskCount" 는 참이고 작업은 아직 돌지 않았다
 * ({@code OrderExecutorDrainPredicateGapTest} ①과 같은 창, v156 {@code O1O3DrainGapDiagTest} 의 붙잡기를 일반화).
 * 미리 만든 코어 작업자가 놀고 있는 상태(전체 실행에서 앞 클래스가 만든 상태)를 재현하려고 교체 직후 코어 작업자를 띄운다.</p>
 *
 * <p>제품 코드·운영 설정은 바꾸지 않는다. 지연은 작업의 순서·결과를 바꾸지 않고 시작만 늦춘다.</p>
 */
public final class DrainGapGate implements ContextCustomizerFactory {

    static final String ENV_MS = "DRAIN_GAP_GATE_MS";
    static final String ENV_BEANS = "DRAIN_GAP_GATE_BEANS";

    /** 붙잡은 횟수 — 진단 출력용. */
    public static final AtomicLong HELD = new AtomicLong();

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        String ms = System.getenv(ENV_MS);
        if (ms == null || ms.isBlank()) return null;
        String beans = System.getenv(ENV_BEANS);
        Set<String> names = Arrays.stream((beans == null || beans.isBlank() ? "orderExecutor,marketDataExecutor" : beans).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        return new Customizer(Long.parseLong(ms.trim()), names);
    }

    private record Customizer(long holdMs, Set<String> beans) implements ContextCustomizer {
        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            context.getBeanFactory().addBeanPostProcessor(new Swap(holdMs, beans));
        }
    }

    private record Swap(long holdMs, Set<String> beans) implements BeanPostProcessor {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
            if (!beans.contains(beanName) || !(bean instanceof ThreadPoolTaskExecutor original)) return bean;
            ThreadPoolExecutor p = original.getThreadPoolExecutor();
            int capacity = p.getQueue().remainingCapacity() + p.getQueue().size();
            ThreadPoolTaskExecutor gated = new ThreadPoolTaskExecutor() {
                @Override
                protected BlockingQueue<Runnable> createQueue(int queueCapacity) {
                    return new LinkedBlockingQueue<>(queueCapacity) {
                        @Override
                        public Runnable take() throws InterruptedException {
                            return hold(super.take());
                        }

                        @Override
                        public Runnable poll(long timeout, TimeUnit unit) throws InterruptedException {
                            return hold(super.poll(timeout, unit));
                        }

                        private Runnable hold(Runnable task) throws InterruptedException {
                            if (task != null) {
                                HELD.incrementAndGet();
                                Thread.sleep(holdMs);
                            }
                            return task;
                        }
                    };
                }
            };
            gated.setCorePoolSize(p.getCorePoolSize());
            gated.setMaxPoolSize(p.getMaximumPoolSize());
            gated.setKeepAliveSeconds((int) p.getKeepAliveTime(TimeUnit.SECONDS));
            gated.setQueueCapacity(capacity);
            gated.setThreadNamePrefix(original.getThreadNamePrefix());
            gated.setRejectedExecutionHandler(p.getRejectedExecutionHandler());
            gated.setWaitForTasksToCompleteOnShutdown(true);
            gated.setAwaitTerminationSeconds(30);
            gated.initialize();
            gated.getThreadPoolExecutor().prestartAllCoreThreads();
            original.shutdown();
            System.out.println("[DrainGapGate] " + beanName + " 교체 — 꺼낸 뒤 " + holdMs + "ms 머묾 · core " + p.getCorePoolSize()
                    + " max " + p.getMaximumPoolSize() + " queue " + capacity + " 거절 " + p.getRejectedExecutionHandler().getClass().getSimpleName());
            return gated;
        }
    }
}
