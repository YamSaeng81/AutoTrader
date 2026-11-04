package com.cryptoautotrader.api.news;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 뉴스 출처 한 번의 수집 결과 (N11, 2026-11-04).
 *
 * <ul>
 *   <li>{@link Status#SUCCESS} — 응답을 받아 파싱까지 끝났다. 0건도 성공이다.</li>
 *   <li>{@link Status#PARTIAL} — 일부 항목을 만든 뒤 실패했다. 만든 항목은 그대로 둔다.</li>
 *   <li>{@link Status#FAILED} — 항목 없이 실패했다(연결 오류·HTTP 비200·필요한 필드 없는 응답 등).</li>
 * </ul>
 *
 * <p>{@code failureKind}는 짧은 분류값만 담는다: {@code HTTP_<코드>}, {@code INVALID_RESPONSE},
 * {@code SOURCE_REPORTED_FAILURE}, 예외 클래스의 단순 이름(예: {@code IOException}), {@code UNSUPPORTED_TYPE}, {@code NULL_RESULT}.
 * 토큰·전체 URL·응답 본문·예외 메시지는 넣지 않는다.
 * {@code decidedAt}은 출처가 결과를 판정한 시각이다.
 */
public record NewsFetchResult(Status status, List<NewsItem> items, String failureKind, Instant decidedAt) {

    public enum Status { SUCCESS, PARTIAL, FAILED }

    public static final String INVALID_RESPONSE = "INVALID_RESPONSE";
    public static final String UNSUPPORTED_TYPE = "UNSUPPORTED_TYPE";
    public static final String NULL_RESULT = "NULL_RESULT";
    /** 응답 본문이 스스로 실패를 밝혔다(예: 업비트 {@code success:false}). 본문 내용은 담지 않는다. */
    public static final String SOURCE_REPORTED_FAILURE = "SOURCE_REPORTED_FAILURE";

    public NewsFetchResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(decidedAt, "decidedAt");
        items = items == null ? List.of() : List.copyOf(items);
        if (status == Status.SUCCESS && failureKind != null) {
            throw new IllegalArgumentException("성공 결과에는 실패 종류가 없다");
        }
        if (status != Status.SUCCESS && (failureKind == null || failureKind.isBlank())) {
            throw new IllegalArgumentException("실패·부분 결과에는 실패 종류가 필요하다");
        }
        if (status == Status.FAILED && !items.isEmpty()) {
            throw new IllegalArgumentException("항목이 있으면 FAILED 가 아니라 PARTIAL 이다");
        }
        if (status == Status.PARTIAL && items.isEmpty()) {
            throw new IllegalArgumentException("항목이 없으면 PARTIAL 이 아니라 FAILED 다");
        }
    }

    public static NewsFetchResult success(List<NewsItem> items) {
        return new NewsFetchResult(Status.SUCCESS, items, null, Instant.now());
    }

    public static NewsFetchResult failed(String failureKind) {
        return new NewsFetchResult(Status.FAILED, List.of(), failureKind, Instant.now());
    }

    public static NewsFetchResult httpStatus(int statusCode) {
        return failed("HTTP_" + statusCode);
    }

    /** 예외로 끝난 수집. 이미 만든 항목이 있으면 PARTIAL, 없으면 FAILED. 예외 메시지는 담지 않는다. */
    public static NewsFetchResult fromException(List<NewsItem> collectedSoFar, Exception e) {
        String kind = e.getClass().getSimpleName();
        if (kind.isEmpty()) kind = e.getClass().getName(); // 익명 클래스
        if (collectedSoFar == null || collectedSoFar.isEmpty()) {
            return new NewsFetchResult(Status.FAILED, List.of(), kind, Instant.now());
        }
        return new NewsFetchResult(Status.PARTIAL, collectedSoFar, kind, Instant.now());
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
