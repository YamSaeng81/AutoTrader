package com.cryptoautotrader.api.support;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 🔒 {@link NetworkIsolationGuard} 의 설치·복원과 클래스 단위 실패 — <b>시험 전용</b> (총괄 v1.166 §3-3, 2026-11-13).
 *
 * <p>{@code junit-platform.properties} 의 확장 자동 감지로 모든 Jupiter 클래스에 붙는다. 엔진이 확장을 등록할 때(첫 시험 클래스·
 * 어떤 스프링 컨텍스트·HttpClient 생성보다 먼저) 정적 초기화에서 감시를 설치하고, 루트 저장소가 닫힐 때(모든 시험 뒤, 예외 때도)
 * 요약을 쓰고 원래 보안 관리자로 되돌린다.</p>
 *
 * <p>클래스가 끝날 때 앞 확인 뒤 새로 막힌 시도가 있으면 그 클래스를 실패시킨다. 캐시된 컨텍스트의 비동기 작업이 다음 클래스 중에
 * 연결하면 그 클래스에 귀속되므로 귀속은 근사다 — 시도 원문에 스레드와 앱 호출 위치를 남긴다.</p>
 */
public class NetworkIsolationExtension implements BeforeAllCallback, AfterAllCallback {

    static {
        NetworkIsolationGuard.installIfEnabled();
    }

    private static final AtomicInteger CURSOR = new AtomicInteger();
    private static final AtomicInteger CLASSES = new AtomicInteger();

    @Override
    public void beforeAll(ExtensionContext context) {
        context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL)
                .getOrComputeIfAbsent(NetworkIsolationExtension.class.getName() + ".restore", k -> new Restore(),
                        ExtensionContext.Store.CloseableResource.class);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (context.getParent().map(p -> p.getParent().isPresent()).orElse(false)) return; // @Nested 는 바깥 클래스가 확인한다
        CLASSES.incrementAndGet();
        int now = NetworkIsolationGuard.count();
        List<String> fresh = NetworkIsolationGuard.since(CURSOR.getAndSet(now));
        if (!fresh.isEmpty()) {
            throw new AssertionError("[NET-GUARD] 예상하지 않은 외부 연결 " + fresh.size() + "건(차단됨, 귀속은 근사): " + fresh);
        }
    }

    private static final class Restore implements ExtensionContext.Store.CloseableResource {
        @Override
        public void close() {
            boolean wasInstalled = NetworkIsolationGuard.isInstalled();
            List<String> all = NetworkIsolationGuard.since(0);
            NetworkIsolationGuard.uninstall();
            String summary = "installed_during_run=" + wasInstalled
                    + "\ndisabled_reason=" + NetworkIsolationGuard.disabledReason()
                    + "\nclasses_checked=" + CLASSES.get()
                    + "\nblocked_external_attempts=" + all.size()
                    + "\nallowed_jndi_nameserver_probes=" + NetworkIsolationGuard.probes().size()
                    + "\nrestored_security_manager=" + System.getSecurityManager()
                    + "\n" + String.join("\n", all)
                    + (NetworkIsolationGuard.probes().isEmpty() ? "" : "\n--- 허용한 권한 확인(패킷 없음)\n"
                            + String.join("\n", NetworkIsolationGuard.probes()))
                    + "\n";
            System.out.println("[NET-GUARD] 요약\n" + summary);
            try {
                Path dir = Path.of("build", "test-results", "test");
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("NET-GUARD-summary.txt"), summary, StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.out.println("[NET-GUARD] 요약 파일 기록 실패: " + e);
            }
        }
    }
}
