package com.cryptoautotrader.api.llm;

import com.cryptoautotrader.api.entity.LlmCallLogEntity;
import com.cryptoautotrader.api.entity.LlmTaskConfigEntity;
import com.cryptoautotrader.api.repository.LlmCallLogRepository;
import com.cryptoautotrader.api.repository.LlmTaskConfigRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * LLM 작업 라우터.
 *
 * <p>작업(LlmTask) → DB 설정 조회 → 적절한 LlmProvider 선택 → complete() 호출.
 * DB 설정은 런타임에 변경되면 즉시 반영된다 (재시작 불필요).
 *
 * <p>사용 예:
 * <pre>
 *   LlmResponse resp = llmTaskRouter.route(LlmTask.LOG_SUMMARY, systemPrompt, userPrompt);
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class LlmTaskRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmTaskRouter.class);

    private final LlmTaskConfigRepository taskConfigRepo;
    private final LlmProviderRegistry providerRegistry;
    private final LlmCallLogRepository callLogRepo;

    /**
     * 작업 유형에 맞는 provider로 LLM 요청을 라우팅한다.
     *
     * @param task         작업 유형 (LOG_SUMMARY, SIGNAL_ANALYSIS 등)
     * @param systemPrompt 시스템 프롬프트
     * @param userPrompt   유저 입력
     * @return LLM 응답 (실패 시 success=false)
     */
    public LlmResponse route(LlmTask task, String systemPrompt, String userPrompt) {
        LlmTaskConfigEntity taskConfig = taskConfigRepo.findByTaskName(task.name()).orElse(null);

        if (taskConfig == null) {
            log.warn("[LlmTaskRouter] task 설정 없음: {} → MockProvider 사용", task);
            return providerRegistry.get("MOCK").complete(
                    LlmRequest.builder()
                            .systemPrompt(systemPrompt)
                            .userPrompt(userPrompt)
                            .build());
        }

        if (!taskConfig.isEnabled()) {
            log.debug("[LlmTaskRouter] task 비활성화: {}", task);
            return LlmResponse.error(task.name(), "LLM 작업이 비활성화 상태입니다: " + task);
        }

        LlmProvider provider = providerRegistry.get(taskConfig.getProviderName());

        LlmRequest request = LlmRequest.builder()
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .model(taskConfig.getModel())
                .temperature(taskConfig.getTemperature() != null
                        ? taskConfig.getTemperature().doubleValue() : null)
                .maxTokens(taskConfig.getMaxTokens() > 0 ? taskConfig.getMaxTokens() : null)
                .build();

        log.debug("[LlmTaskRouter] 라우팅 — task={} provider={} model={}",
                task, taskConfig.getProviderName(), taskConfig.getModel());

        long startMs = System.currentTimeMillis();
        LlmResponse response = provider.complete(request);
        long durationMs = System.currentTimeMillis() - startMs;

        if (!response.isSuccess()) {
            log.warn("[LlmTaskRouter] 응답 실패 — task={} provider={} error={}",
                    task, taskConfig.getProviderName(), response.getErrorMessage());
        }

        saveLog(task, request, response, durationMs);

        return response;
    }

    private void saveLog(LlmTask task, LlmRequest request, LlmResponse response, long durationMs) {
        try {
            callLogRepo.save(LlmCallLogEntity.builder()
                    .taskName(task.name())
                    .providerName(response.getProviderName())
                    .modelUsed(response.getModelUsed())
                    .systemPrompt(request.getSystemPrompt())
                    .userPrompt(request.getUserPrompt())
                    .responseContent(response.getContent())
                    .promptTokens(response.getPromptTokens())
                    .completionTokens(response.getCompletionTokens())
                    .success(response.isSuccess())
                    .errorMessage(response.getErrorMessage())
                    .durationMs(durationMs)
                    .calledAt(Instant.now())
                    .build());
        } catch (Exception e) {
            log.warn("[LlmTaskRouter] 로그 저장 실패 (호출 자체는 성공): {}", e.getMessage());
        }
    }
}
