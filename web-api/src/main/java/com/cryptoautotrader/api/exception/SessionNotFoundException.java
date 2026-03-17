package com.cryptoautotrader.api.exception;

public class SessionNotFoundException extends RuntimeException {
    public SessionNotFoundException(Long sessionId) {
        super("세션을 찾을 수 없습니다: id=" + sessionId);
    }
}
