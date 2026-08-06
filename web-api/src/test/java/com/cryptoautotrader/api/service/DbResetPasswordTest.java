package com.cryptoautotrader.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB 초기화 비밀번호가 소스 하드코딩 → 환경변수(DB_RESET_PASSWORD)로 옮겨진 뒤의 계약을 잠근다.
 *
 * <p>핵심은 <b>fail-closed</b>다: 환경변수를 안 넣고 배포했을 때 "아무 비밀번호나 통과"가 아니라
 * "전부 거부"가 되어야 한다. JdbcTemplate은 쓰지 않는 경로라 null로 둔다.</p>
 */
class DbResetPasswordTest {

    private static DbResetService withPassword(String configured) {
        return new DbResetService(null, configured);
    }

    @Test
    @DisplayName("설정된 비밀번호와 일치하면 통과한다")
    void matchingPasswordPasses() {
        assertThat(withPassword("s3cret").checkPassword("s3cret")).isTrue();
    }

    @Test
    @DisplayName("비밀번호가 다르면 거부한다")
    void wrongPasswordRejected() {
        DbResetService svc = withPassword("s3cret");
        assertThat(svc.checkPassword("wrong")).isFalse();
        assertThat(svc.checkPassword("s3cre")).isFalse();   // 접두사
        assertThat(svc.checkPassword("s3secret")).isFalse();
        assertThat(svc.checkPassword("")).isFalse();
        assertThat(svc.checkPassword(null)).isFalse();
    }

    @Test
    @DisplayName("환경변수 미설정이면 어떤 입력도 거부한다 (fail-closed)")
    void unconfiguredRejectsEverything() {
        for (String configured : new String[] { null, "", "   " }) {
            DbResetService svc = withPassword(configured);
            assertThat(svc.checkPassword("anything")).as("configured=%s", configured).isFalse();
            assertThat(svc.checkPassword("")).as("configured=%s", configured).isFalse();
            assertThat(svc.checkPassword(null)).as("configured=%s", configured).isFalse();
        }
    }

    @Test
    @DisplayName("과거 하드코딩되어 있던 비밀번호는 더 이상 통하지 않는다")
    void legacyHardcodedPasswordNoLongerWorks() {
        // 소스에 박혀 있던 값이 git 이력에 남아 있으므로, 설정과 무관하게 통과하면 안 된다.
        assertThat(withPassword("s3cret").checkPassword("!Iloveyhde1")).isFalse();
        assertThat(withPassword("").checkPassword("!Iloveyhde1")).isFalse();
    }
}
