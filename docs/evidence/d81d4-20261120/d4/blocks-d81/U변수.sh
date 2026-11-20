M=~/maint-d81; mkdir -p "$M"; OPS=~/ops-scripts; UB=D81D4   # 총괄 v1.198 §3 판 — 후보 d81c3f2f backend 전용 배포 D4(미승인·실행 금지). 생성기 mkd81.py 가 V172D4 원문(ce6df2fa, 이후 U2확인 보정 d456788f·결과 7fea1b89 수용)에서 지정 부분만 바꿨다. 원판 = 현재 운영 0e1d2a02 / backend 2c5d8427 / frontend 9d7f123a / DB V96. frontend 교체·빌드·태그 변경 없음, DDL 없음(V96 기적용). U 블록 전부가 이 표지를 확인한다
OLD=0e1d2a021ab2f3c759a9b2be9c4f09430f93c80d; NEW=d81c3f2f91c0239aacb0e1176dde9b898f4d7466
IMGOLD=sha256:2c5d84276cdbf01f718077e3d2d0475f6af5982d7afb95082750fcebe7f5979f   # 운영 0e1d2a02 backend 대상 ID(V172D4 U1 빌드, ~/maint-v172/image-new.txt · 427차 서버 보존본 maint-v172/image-new-chain.txt 11-16 관측) — U0 가 실행 이미지·그 기록 파일과 같은지 확인한다. 현재값이라고 가정하지 않는다
IMGPREV8=sha256:426a09a20afc9e16cff8b7a569d4e04f18ed47cefb9242f44e3b9ea9666153a1   # #26 이미지 — 이전 복구 태그 autotrader-backend:1899b49 가 가리킨다(D3 U1 이 만듦, 보존, 바꾸지 않는다)
IMGPREV7=sha256:200f2f5727e90204574fd9965a5cc06dfc5d317e98c13b200de2ac7201378ae6   # #25 이미지 — 이전 복구 태그 autotrader-backend:ee509cf 가 가리킨다(#26 U1 이 만듦, 보존, 바꾸지 않는다)
IMGPREV6=sha256:e51b512a72cc3740313bb2a9e6f369d4f480554c588f322cff518c827f9c7be6   # #24 이미지 — 이전 복구 태그 autotrader-backend:7a94472 가 가리킨다(#25 U1 이 만듦, 보존, 바꾸지 않는다)
IMGPREV5=sha256:9818254ca137a79245d1b30f07c0e193ca4d9a1ac028e0b26a16af6853616b62   # #23 이미지 — 이전 복구 태그 autotrader-backend:1103f48 가 가리킨다(#24 T1 이 만듦, 보존, 바꾸지 않는다)
IMGPREV9=sha256:52e12428a60df5413d6e495024220bbd4c8fa07830549556f0b47bbe6ad39055   # #27 이미지(b5cd1d0) — 보존 태그 autotrader-backend:b5cd1d0 이 가리킨다(V168·V172 U1). D81D4 의 복구 대상이 아니다 — 보존만, 만들거나 바꾸지 않는다
FEPREV9=sha256:837db515e0eb8b9cb4bcf8f03ed3b98adf9f8cdb176d13777949c792d10b5017   # b5cd1d0 판 frontend 대상 — 보존 태그 autotrader-frontend:b5cd1d0 이 가리킨다(V172D4 U1 11-16 이 만듦). 보존만
IMGPREV=sha256:618046540764e49aa40efef7ec950f28cd5882e8bf7d6bdfdc3da0ed1f0112f9   # #19 이미지 — 이전 복구 태그 autotrader-backend:ea44900 가 가리킨다(보존, 바꾸지 않는다)
IMGPREV2=sha256:fd3ac351bcf508d465b9c2f6024ba978273603729f3e6cecba40a639a2f4dcb6   # #20 이미지 — 이전 복구 태그 autotrader-backend:b1d7664 가 가리킨다(보존, 바꾸지 않는다)
IMGPREV3=sha256:67294e80ada31d346e36caeb01a2c259b6b5514357c804977ba1d20fba6aec07   # #21 이미지 — 이전 복구 태그 autotrader-backend:6995c52 가 가리킨다(#22 P1 이 만듦, 보존, 바꾸지 않는다)
IMGPREV4=sha256:9cb5d84f0bfde5208f24f591ad291dacabdfa691a0b2e1f057c4f8c3e1720fb7   # #22 이미지 — 이전 복구 태그 autotrader-backend:fe01207 가 가리킨다(#23 R1 이 만듦, 보존, 바꾸지 않는다)
LS=8bfe8a9fa896f400b3fb6681fef021c99d6ba43b; LSUM=02a8585021fb2a1a52d9b3b56b60e957cb71b9ca3c11bf819595df15cf334f1c   # 지금 설치 장부 — 바꾸지 않는다
CK=c669ccdc6e248ce8e55e9a7ac7405197d266fa18; CKSH=ff0a5d7dbc9e250d364a7f32f80106454b6f4b939d37c578f8192e8206e7c1cc; CKSQL=653b22bab04b68c50bf9daf2a54f2c281f9c57eaf6795076225ed39d62d48fa8   # 정기 점검 설치본 — 바꾸지 않는다
CRON=f2dce2809c09aa4466a95200caa19547118e444b4ac90b4363e20a158dee9b5b   # crontab — 바꾸지 않는다
h256() { sha256sum < "$1" | cut -d' ' -f1 ; }
v96h() { local o; o=$($PSQL -At -c "SELECT count(*) || '|' || coalesce(string_agg(version || ':' || success || ':' || coalesce(checksum::text, 'NULL'), ',' ORDER BY installed_rank), '-') FROM flyway_schema_history WHERE version::int >= 95") || { echo "🔴 V95 이후 이력 조회 실패" >&2; return 2; }; printf 'V95 이후 이력(행수|내용): %s\n' "$o"; [ "$o" = "1|96:true:$V96SUM" ] ; }   # U2확인(d456788f 1c9eab35) 계약 — 행수·내용 한 SELECT, 체크섬 NULL 은 'NULL' 글자. 통과는 1|96:true:-424836241 하나뿐(종료 1 다름 · 2 조회 실패)
DIFFN="$(printf '%s\n' web-api/src/main/java/com/cryptoautotrader/api/dto/DynamicSessionRequest.java \
  web-api/src/main/java/com/cryptoautotrader/api/entity/ExitReason.java \
  web-api/src/main/java/com/cryptoautotrader/api/report/LogAnalyzerService.java \
  web-api/src/main/java/com/cryptoautotrader/api/repository/OrderRepository.java \
  web-api/src/main/java/com/cryptoautotrader/api/repository/PositionRepository.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/CircuitBreakerResult.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/DynamicEntryLimits.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/DynamicTradingService.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/LiveTradingService.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/OrderExecutionEngine.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/OrderSettlementService.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/PaperTradingService.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/RiskManagementService.java \
  web-api/src/main/java/com/cryptoautotrader/api/service/TelegramNotificationService.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/AbcCrossPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/AlignedV147FixesTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/CapitalPoolLimitTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/CostBasisBoundaryPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/CostBasisFollowupPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/CostBasisRiskPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/CostSourceFixture.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/DailySma20LiveOrderPathTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/DynamicBlackSwanCooldownCrossKindTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/DynamicCircuitBreakerTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/DynamicEntryLimitsTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/DynamicTradingModeInputTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/ExitReasonRecordingTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/LiveLossWindowPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/PartialSellRealizedPnlPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/PhantomKeepsConfirmedPnlPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/PositionLimitTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/R5RealCashflowRiskTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/RealBuySummaryPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/RealLossGlobalPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/RealLossThreeFormulaPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/RiskCircuitBreakerOrderTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/SessionKindIsolationTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/SettlementRoundingPgTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/Sma20LiveAlignedGapsTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/StrategyEnablementGateTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/TelegramMinimalAlertTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/WalkForwardGateDisabledSessionCreationTest.java \
  web-api/src/test/java/com/cryptoautotrader/api/service/WalkForwardGateEnabledSessionCreationTest.java)"   # git diff --name-only OLD NEW — 43경로(제품 Java 14·시험 29, 리소스·마이그레이션·프런트·build.gradle 0). mkd81.py 가 고정 Git 에서 기계 생성
RARX="$(printf '%s\n' '변경 BOOT-INF/classes/com/cryptoautotrader/api/dto/DynamicSessionRequest.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/entity/ExitReason$1.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/entity/ExitReason.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/report/LogAnalyzerService$NoTradeFunnel.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/report/LogAnalyzerService$PositionStats.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/report/LogAnalyzerService$RegimeStats.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/report/LogAnalyzerService$SignalStats.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/report/LogAnalyzerService.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/repository/OrderRepository.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/repository/PositionRepository.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/CircuitBreakerResult.class' \
  '추가 BOOT-INF/classes/com/cryptoautotrader/api/service/DynamicEntryLimits.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/DynamicTradingService$1.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/DynamicTradingService$2.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/DynamicTradingService$3.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/DynamicTradingService$BlackSwanGateDecision.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/DynamicTradingService$BuyCandidate.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/DynamicTradingService$EmptyPositionOutcome.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/DynamicTradingService.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/LiveTradingService$1.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/LiveTradingService$EmptyPositionOutcome.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/LiveTradingService$PriceSnapshot.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/LiveTradingService.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/OrderExecutionEngine.class' \
  '추가 BOOT-INF/classes/com/cryptoautotrader/api/service/OrderSettlementService$BuyIncrement.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/OrderSettlementService$Outcome.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/OrderSettlementService$SellAmounts.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/OrderSettlementService.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/PaperTradingService$AlignedEntryExpired.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/PaperTradingService.class' \
  '추가 BOOT-INF/classes/com/cryptoautotrader/api/service/RiskManagementService$ClosedPnl.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/RiskManagementService.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/TelegramNotificationService$Attempt.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/TelegramNotificationService$Outcome.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/TelegramNotificationService$Reply.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/TelegramNotificationService$SendMessageRequest.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/TelegramNotificationService$TradeEvent.class' \
  '변경 BOOT-INF/classes/com/cryptoautotrader/api/service/TelegramNotificationService.class')"   # 운영 0e1d2a02 Git LF 기대 895항목 대 후보 d81c3f2f LF jar 898항목(eae7e1d2…), 비교기 v2 — 변경 35·추가 3·삭제 0(v1.197 수용 목록, 447차 7c62a7db 고정 객체에서 기계 생성)
JARN="(끝) 895 898"   # 총괄 v1.198 §2 확정 — 운영 LF 895항목 → 후보 898항목
JARM=33a06f0edf1767356d206f8d36b5f71b1ffd707102d43936e602ec7a6162d509   # 총괄 v1.197 §2 수용 — 후보 d81c3f2f LF jar 항목 이름·sha256 목록(898줄, dec-final-abc-20261119/jar-d81c3f2f/jar-manifest-d81c3f2f.lf.txt)의 sha256
V96F=web-api/src/main/resources/db/migration/V96__paper_aligned_runtime_state.sql; V96B=5b92eef03548c6bd1feaef2b35451563d5327246; V96SUM=-424836241   # V96 원문 블롭·Flyway 체크섬(로컬 실제 java -jar 기동 두 번에서 같은 값, oldbin-v96-r1·r2)
v96() { [ "$($PSQL -At -c "SELECT max(version::int) FROM flyway_schema_history WHERE success")" = "96" ] ; }
FEIMGOLD=sha256:9d7f123ad6afd75b46d954b7c7031267df5afb3bf6e99660d5a53a3c5d43798e   # 실행 중 frontend 컨테이너 .Image — 427차 서버 보존본 maint-v172/svc-fe.txt(11-16 UF2 뒤 관측, 새로 만든 컨테이너라 대상 ID 와 같다). 현재값이라고 쓰지 않는다 — U0 가 다시 대조하고 다르면 멈춘다
# ── V171D4 이미지 대응(총괄 v1.171 §3) — 운영 읽기 2026-11-16 10:52~11:00 KST(docs/evidence/v171d4-20261116/state-1052) ──
# 서버 Docker 29.9.2 는 containerd 이미지 저장소다. 이미지 참조의 대상 ID(index)·linux/amd64 manifest·config 는 서로 다른 값이다.
# 새로 만든 컨테이너의 .Image 는 대상 ID(backend 743dfeb… = 52e12428)다. 09-22 에 만든 frontend 9a63d15… 의 .Image(d825a9f5)는
# 지금 어떤 이미지 객체(대상 837db515·config 80ce7550)와도 같지 않다. 그래서 실행 컨테이너 대응은 ① 컨테이너 ImageManifestDescriptor
# = 참조 대상의 linux/amd64 manifest ② daemon 이 그 manifest 의 사용 컨테이너로 그 컨테이너를 표시 ③ save 산출물의 manifest→config
# 원문 해시 ④ .Image 가 허용값(옛 컨테이너는 기록값, 새 컨테이너는 대상 ID 또는 config)인지로 확인한다. 태그 이름·Compose 라벨만으로 정하지 않는다.
IMGOLDM=sha256:b3a42cd2dd9947e6dffc5ccec71de077b3d2fccfc810d21796378be73b05aa0c; IMGOLDC=sha256:4016cc389044ba95a88f9048f6b14de7118e276c75f9a524998e2b8baa50ac34   # 운영 backend 대상 2c5d8427 의 linux/amd64 manifest·config — 427차 보존본 image-new-chain.txt(11-16 관측 기대값)
FETOLD=sha256:9d7f123ad6afd75b46d954b7c7031267df5afb3bf6e99660d5a53a3c5d43798e; FEMOLD=sha256:13343943ed4ab0db003bf40e77cb38c9d41e67287a7d51fcb6da818ad6569d93; FECOLD=sha256:11d9a954a3054d3eff3c6d2d798fad55beefce3a166c9335259d8fa6367e4f89   # 실행 중 frontend: 이미지 이름 autotrader-frontend 의 대상 ID·linux/amd64 manifest·config — 427차 보존본 image-fe-new-chain.txt(11-16 관측 기대값)
DAPI=v1.56   # 서버 Docker Engine API 판(29.9.2)
ICSUM=fddd5d1cae1b85e2a002d30afe24044869b79c4983c6cc46fe3363a0aba354f6   # 아래 imgchain.py 원문 sha256 — 블록이 $M/imgchain.py 로 쓰고 쓸 때마다 대조한다
IFS= read -r -d '' ICPY <<'ICEOF' || true
# V171D4 이미지 대응 확인 도구(총괄 v1.171 §3-2). containerd 이미지 저장소에서 이미지 참조의 대상 ID·linux/amd64 manifest·config 와
# 실행 컨테이너의 연결을 확인한다. 하나라도 확인하지 못하면 표준 오류에 '🔴 이미지 대응 거절'을 쓰고 종료 3(표준 출력 없음).
#   api  <대상ID>     표준 입력 = GET /images/<대상ID>/json?manifests=1 응답 → "manifest 사용컨테이너(쉼표, 없으면 -)"
#   save <manifest>   표준 입력 = docker image save --platform linux/amd64 <대상ID> 스트림(디스크에 쓰지 않음) → "config"
#   ctr               표준 입력 = docker inspect --format '{{.Id}} {{.Image}} {{json .ImageManifestDescriptor}}' 한 줄 → "컨테이너ID .Image manifest"
import hashlib, json, re, sys, tarfile
H = re.compile(r"sha256:[0-9a-f]{64}")
CID = re.compile(r"[0-9a-f]{64}")
MAN = "application/vnd.oci.image.manifest.v1+json"
CFG = "application/vnd.oci.image.config.v1+json"
AMD = {"architecture": "amd64", "os": "linux"}


def die(m):
    sys.stderr.write("🔴 이미지 대응 거절: " + m + "\n")
    sys.exit(3)


def plat(p):
    return isinstance(p, dict) and p == AMD


def api(t):
    if not H.fullmatch(t):
        die("대상 ID 형식이 아님 %r" % t)
    try:
        d = json.loads(sys.stdin.buffer.read())
    except ValueError:
        die("API 응답이 JSON 이 아님")
    if not isinstance(d, dict) or d.get("Id") != t or (d.get("Descriptor") or {}).get("digest") != t:
        die("API 응답의 Id·Descriptor 가 조회한 대상 ID 와 다름")
    ms = [m for m in (d.get("Manifests") or []) if isinstance(m, dict) and m.get("Kind") == "image"]
    if len(ms) != 1:
        die("image 종류 manifest 가 정확히 1개가 아님(%d개)" % len(ms))
    m = ms[0]
    desc = m.get("Descriptor") or {}
    data = m.get("ImageData") or {}
    if not H.fullmatch(str(m.get("ID"))) or desc.get("digest") != m.get("ID"):
        die("manifest ID 와 Descriptor digest 가 다름")
    if desc.get("mediaType") != MAN:
        die("manifest 형식이 OCI manifest 가 아님 %r" % desc.get("mediaType"))
    if not plat(desc.get("platform")) or not plat(data.get("Platform")):
        die("manifest 플랫폼이 linux/amd64 가 아님 %r %r" % (desc.get("platform"), data.get("Platform")))
    if m.get("Available") is not True:
        die("manifest 내용이 저장소에 없음(Available 아님)")
    cs = data.get("Containers") or []
    if not isinstance(cs, list) or any(not CID.fullmatch(str(c)) for c in cs) or len(set(cs)) != len(cs):
        die("사용 컨테이너 목록 형식 이상")
    print(m["ID"], ",".join(cs) if cs else "-")


def save(mdig):
    if not H.fullmatch(mdig):
        die("manifest 형식이 아님 %r" % mdig)
    small = {}
    try:
        with tarfile.open(fileobj=sys.stdin.buffer, mode="r|") as t:
            for e in t:
                if e.isfile() and (e.name == "index.json" or (e.name.startswith("blobs/sha256/") and e.size < (1 << 20))):
                    if e.name in small:
                        die("저장 산출물에 같은 이름 중복 " + e.name)
                    small[e.name] = t.extractfile(e).read()
    except tarfile.TarError as x:
        die("저장 산출물을 읽지 못함(%s)" % type(x).__name__)
    for k, b in small.items():
        if k.startswith("blobs/sha256/") and hashlib.sha256(b).hexdigest() != k[len("blobs/sha256/"):]:
            die("blob 원문 해시가 이름과 다름 " + k)
    if "index.json" not in small:
        die("저장 산출물에 index.json 없음")
    idx = json.loads(small["index.json"])
    ms = idx.get("manifests") or []
    if len(ms) != 1 or ms[0].get("digest") != mdig or ms[0].get("mediaType") != MAN or not plat(ms[0].get("platform")):
        die("저장 산출물 index 가 linux/amd64 manifest %s 하나가 아님" % mdig[:19])
    mb = small.get("blobs/sha256/" + mdig[7:])
    if mb is None or len(mb) != ms[0].get("size"):
        die("manifest 원문 없음 또는 크기 다름")
    mj = json.loads(mb)
    c = mj.get("config") or {}
    if mj.get("mediaType") != MAN or c.get("mediaType") != CFG or not H.fullmatch(str(c.get("digest"))):
        die("manifest 의 config 기술자 형식 이상")
    cb = small.get("blobs/sha256/" + c["digest"][7:])
    if cb is None or len(cb) != c.get("size"):
        die("config 원문 없음 또는 크기 다름")
    cj = json.loads(cb)
    if cj.get("os") != "linux" or cj.get("architecture") != "amd64" or cj.get("variant"):
        die("config 플랫폼이 linux/amd64 가 아님 %r/%r" % (cj.get("os"), cj.get("architecture")))
    print(c["digest"])


def ctr():
    line = sys.stdin.read().strip()
    p = line.split(" ", 2)
    if len(p) != 3 or not CID.fullmatch(p[0]) or not H.fullmatch(p[1]):
        die("컨테이너 조회 줄 형식 이상 %r" % line[:120])
    try:
        d = json.loads(p[2])
    except ValueError:
        die("ImageManifestDescriptor 가 JSON 이 아님")
    if not isinstance(d, dict) or d.get("mediaType") != MAN or not plat(d.get("platform")) or not H.fullmatch(str(d.get("digest"))):
        die("컨테이너 ImageManifestDescriptor 가 linux/amd64 OCI manifest 가 아님 %r" % (d if isinstance(d, dict) else p[2][:80]))
    print(p[0], p[1], d["digest"])


if len(sys.argv) == 3 and sys.argv[1] == "api":
    api(sys.argv[2])
elif len(sys.argv) == 3 and sys.argv[1] == "save":
    save(sys.argv[2])
elif len(sys.argv) == 2 and sys.argv[1] == "ctr":
    ctr()
else:
    die("사용법: api <대상ID> | save <manifest> | ctr")
ICEOF
icpy() { [ -e "$M/imgchain.py" ] || printf '%s' "$ICPY" > "$M/imgchain.py"; [ "$(h256 "$M/imgchain.py")" = "$ICSUM" ] || { echo "🔴 $M/imgchain.py 원문 해시가 ICSUM 과 다름" >&2; return 9; } ; }
rchain() {   # rchain <이미지 참조> → "대상ID manifest config 사용컨테이너(쉼표, 없으면 -)" 한 줄. 하나라도 확인 못 하면 출력 없이 0 아닌 종료
  local t j a c
  icpy || return 9
  t=$(docker image inspect --format '{{.Id}}' "$1") || return 4
  j=$(curl -sf --unix-socket /var/run/docker.sock "http://localhost/$DAPI/images/$t/json?manifests=1") || { echo "🔴 이미지 API 조회 실패 $1" >&2; return 5; }
  a=$(printf '%s' "$j" | python3 -I "$M/imgchain.py" api "$t") || return 6
  c=$(docker image save --platform linux/amd64 "$t" | python3 -I "$M/imgchain.py" save "${a%% *}") || return 7
  echo "$t ${a%% *} $c ${a#* }"
}
cchain() {   # cchain <서비스> → "컨테이너ID .Image manifest"
  local id
  icpy || return 9
  id=$($DC ps -q "$1") && [ -n "$id" ] || { echo "🔴 $1 컨테이너 조회 실패" >&2; return 4; }
  docker inspect --format '{{.Id}} {{.Image}} {{json .ImageManifestDescriptor}}' "$id" | python3 -I "$M/imgchain.py" ctr
}
isimg() {   # isimg <서비스> "<허용 .Image 값들>" <대상ID> <manifest> <config> — 실행 컨테이너가 그 이미지인지
  [ $# = 5 ] || { echo "🔴 isimg 인자 수 $#(기록 파일 없음?)" >&2; return 1; }
  local c r
  c=$(cchain "$1") && r=$(rchain "$3") || return 1
  set -- "$1" "$2" "$3" "$4" "$5" $c $r
  [ $# = 12 ] || { echo "🔴 $1: 대응 조회 결과 형식 이상" >&2; return 1; }
  [ "$9" = "$3" ] && [ "${10}" = "$4" ] && [ "${11}" = "$5" ] && [ "$8" = "$4" ] || { echo "🔴 $1: manifest·config 대응 다름(컨테이너 manifest ${8:7:12} / 기대 ${4:7:12}·${5:7:12}, 조회 ${10:7:12}·${11:7:12})" >&2; return 1; }
  case ",${12}," in *",$6,"*) ;; *) echo "🔴 $1: daemon 의 manifest 사용 컨테이너 목록에 실행 컨테이너 ${6:0:12} 없음" >&2; return 1 ;; esac
  case " $2 " in *" $7 "*) ;; *) echo "🔴 $1: 컨테이너 .Image ${7:7:12} 가 허용값이 아님" >&2; return 1 ;; esac
}
ok3() { cut -d' ' -f1-3 "$1" ; }   # 대응 기록 파일 → "대상 manifest config"
okimg() { echo "$(cut -d' ' -f1 "$1") $(cut -d' ' -f3 "$1")" ; }   # 새로 만든 컨테이너의 .Image 허용값 = 대상 ID 또는 config
# ── V172D4(총괄 v1.172 §3 R1·R2) — 조회 실패를 다른 상태(태그 없음·원판 아님)로 추정하지 않는다 ──
same3() {   # same3 <이미지 참조> <대응 기록 파일> → 0 같음 · 1 대응 다름(조회는 성공) · 2 조회 불명(기록 없음·이름/API/save 조회 실패)
  local r
  [ -s "$2" ] || { echo "🔴 대응 기록 없음 $2" >&2; return 2; }
  r=$(rchain "$1") || { echo "🔴 $1 대응 조회 불명" >&2; return 2; }
  [ "$(echo "$r" | cut -d' ' -f1-3)" = "$(ok3 "$2")" ] || { echo "🔴 $1 대응이 기록($2)과 다름" >&2; return 1; }
}
tagq() {   # tagq <태그 참조> → 0 있음(API 200·Id) · 1 명시적 부재(API 404·본문 {"message":"No such image: <참조>"}) · 2 조회 불명(소켓·권한·daemon·API·형식)
  # 운영 응답 형식(읽기 2026-11-16 12:58 KST, docs/evidence/v172d4-20261116/state-1258): 없음 404 위 본문 · 있음 200 {"Id":"sha256:…" · 소켓 실패 curl 종료 7
  local o c
  o=$(curl -s -w '\n%{http_code}' --unix-socket /var/run/docker.sock "http://localhost/$DAPI/images/$1/json") || { c=$?; echo "🔴 $1 태그 조회 불명(curl 종료 $c)" >&2; return 2; }
  c=${o##*$'\n'}; o=${o%$'\n'*}; o=${o%$'\n'}
  if [ "$c" = 200 ] && [[ "$o" =~ ^\{\"Id\":\"sha256:[0-9a-f]{64}\" ]]; then return 0; fi
  if [ "$c" = 404 ] && [ "$o" = "{\"message\":\"No such image: $1\"}" ]; then return 1; fi
  echo "🔴 $1 태그 조회 불명(HTTP $c ${o:0:120})" >&2; return 2
}
tagkeep() {   # tagkeep <보존 태그> <대응 기록 파일> → 0 있음·기록과 같음 · 1 명시적 부재 · 2 다른 대상을 가리킴 · 3 조회 불명
  local q=0 s=0
  tagq "$1" || q=$?
  case "$q" in
    0) same3 "$1" "$2" || s=$?; case "$s" in 0) return 0 ;; 1) return 2 ;; *) return 3 ;; esac ;;
    1) return 1 ;;
    *) return 3 ;;
  esac
}
namecls() {   # namecls <이미지 이름> <원판 대응 기록> <이번 빌드 대응 기록(없을 수 있음)> → 표준 출력 원판|새판|제3. 조회 불명은 출력 없이 종료 2
  # 새판 = U1 이 그 빌드 직후 대응을 확인해 기록한 대상·manifest·config 와 같을 때만. 빌드 실패로 기록이 없는데 이름이 바뀌었으면 제3(출처 미확인)
  local r
  [ -s "$2" ] || { echo "🔴 원판 대응 기록 없음 $2" >&2; return 2; }
  r=$(rchain "$1") || { echo "🔴 $1 대응 조회 불명 — 원판·새판으로 추정하지 않는다" >&2; return 2; }
  r=$(echo "$r" | cut -d' ' -f1-3)
  if [ "$r" = "$(ok3 "$2")" ]; then echo 원판
  elif [ -s "$3" ] && [ "$r" = "$(ok3 "$3")" ]; then echo 새판
  else echo "🔴 $1 대상 ${r:7:12}… 은 원판·이번 빌드 기록 어느 것도 아님(제3의 대상 또는 출처 미확인)" >&2; echo 제3; fi
}
GS=e97ca31b23dec54db7c962395d2f8e60cc92afb0   # 관문 4판·배포 전 읽기 v3 를 담은 main 고정 커밋(445차, 총괄 v1.198 수용). 꺼낸 원문 해시가 GATESQL·GATESH·PFSUM 과 다르면 U0·U되돌림이 멈춘다
GTP=docs/evidence/dec-costbasis-followup-20261119/rollback-gate-v4-draft; GATESQL=b24239f82f09a6fcfcf36026a10614d3d7e7df3687fd720e0336fe1b84d77da0; GATESH=0b391e58180d334f1f0d38ac88f7186ae03a6b233c945b54df19eaba8aa16906   # 상태 기반 복구 관문 4판(3판 + STOP_INCOMPATIBLE) 원문 해시
PFP=docs/evidence/dec-costbasis-followup-20261119/predeploy-v3/predeploy_read.v3.sql; PFSUM=ca6e7c8d6cc67ee967ac21b7847f7230cc859dc3cb9dc895da6f7694dfe26aa3   # 배포 전 읽기 v3(R1 판정 불가·R2 손실 합·R3 세션 연속 손실 입력) 원문 해시 — 판정 줄이 없어 아래 pf3ok 가 가른다
gsok() { case "$GS" in 미정*|"") return 1 ;; esac; git cat-file -e "$GS^{commit}" 2>/dev/null ; }
# ── D81D4(총괄 v1.198 §3) — 배포 전 읽기 v3 판정. 원문 출력을 바꾸지 않고 개수만 낸다(가격·손익 값·행 원문은 출력하지 않는다) ──
pf3ok() { python3 - "$1" <<'PYEOF'
# 인자: $PSQL -At -F ' | ' < predeploy_read.v3.sql 의 출력 파일. 기대: 1~2줄 R1(LIVE|정수, DYNAMIC|정수) · 3~4줄 R2(LIVE|수, DYNAMIC|수) · 5줄부터 R3 16열(0행 이상).
# 종료 0 = 진행 가능(R1 두 종류 0, R3 판정 불가 행 0) · 1 = 🔴 진입 거절·판정 불가 예상(보고) · 3 = 형식 이상(조회 결과로 보지 않는다).
# R3 판정 불가(보수적): 무거래 확정이 아닌데 체결BUY 0·확정SELL 0·원가 미상 BUY·필수값 미상 SELL·원가/수량 합 없음·매도수량<매수수량 중 하나. 연속 손실 한도 안 여부는 여기서 줄이지 않는다.
import re, sys
from decimal import Decimal, InvalidOperation
L = open(sys.argv[1], encoding='utf-8').read().split('\n')
if L and L[-1] == '':
    L.pop()
def bad(m):
    print('🔴 배포 전 읽기 v3 형식 이상: ' + m); sys.exit(3)
if len(L) < 4:
    bad('줄 수 %d < 4' % len(L))
INT = re.compile(r'^[0-9]+$'); NUM = re.compile(r'^-?[0-9]+(\.[0-9]+)?$')
r1, r2 = {}, {}
for i, k, pat, d in ((0, 'LIVE', INT, r1), (1, 'DYNAMIC', INT, r1), (2, 'LIVE', NUM, r2), (3, 'DYNAMIC', NUM, r2)):
    f = L[i].split(' | ')
    if len(f) != 2 or f[0] != k or not pat.match(f[1]):
        bad('%d줄(%s)' % (i + 1, 'R1' if i < 2 else 'R2'))
    d[k] = f[1]
def dec(x):
    try:
        return Decimal(x)
    except InvalidOperation:
        return None
rows, und, sess = 0, 0, {}
for i, x in enumerate(L[4:], 5):
    f = x.split(' | ')
    if len(f) != 16 or f[10] not in ('LIVE', 'DYNAMIC') or not all(INT.match(f[j]) for j in (0, 1, 2, 5, 8, 9, 11, 15)):
        bad('R3 %d줄(열 %d)' % (i, len(f)))
    rows += 1
    if f[9] == '1':
        continue
    bq, sq = dec(f[4]) if f[4] else None, dec(f[7]) if f[7] else None
    u = f[1] == '0' or f[5] == '0' or f[2] != '0' or f[8] != '0' or f[3] == '' or bq is None or sq is None or sq < bq
    if u:
        und += 1; s = f[10] + ':' + f[11]; sess[s] = sess.get(s, 0) + 1
print('R1 판정 불가(30일) LIVE %s · DYNAMIC %s' % (r1['LIVE'], r1['DYNAMIC']))
print('R2 손실 합(30일, 기록만) LIVE %s · DYNAMIC %s' % (r2['LIVE'], r2['DYNAMIC']))
print('R3 RUNNING REAL 세션 청산 행 %d · 판정 불가 %d%s' % (rows, und, (' (' + ', '.join('%s %d' % kv for kv in sorted(sess.items())) + ')') if sess else ''))
if r1['LIVE'] != '0' or r1['DYNAMIC'] != '0' or und:
    print('판정 | 🔴 진행 불가 — R1 판정 불가 또는 R3 판정 불가 행이 있다(배포 직후 그 종류·세션의 신규 REAL 진입 거절 예상)'); sys.exit(1)
print('판정 | 진행 가능(R1 두 종류 0 · R3 판정 불가 0)')
PYEOF
}
oldtag() {   # oldtag <옛 보존 태그> <기대 대상 ID> → 0 있음·같음 · 1 명시적 부재(API 404) · 2 다른 대상 · 3 조회 불명. 만들거나 바꾸지 않는다(이번 복구 대상이 아님)
  local q=0 id
  tagq "$1" || q=$?
  case "$q" in 0) ;; 1) return 1 ;; *) return 3 ;; esac
  id=$(docker image inspect --format '{{.Id}}' "$1") || { echo "🔴 $1 대상 ID 조회 불명" >&2; return 3; }
  [ "$id" = "$2" ] || { echo "🔴 $1 이 ${id:7:12}… 를 가리킨다(기대 ${2:7:12}…)" >&2; return 2; }
}
# 기존 뉴스 출처 ERROR 원문 4개(후보에서도 바뀌지 않음). 정기 점검에서는 미분류 🔴 그대로다
NEWSERR='\[(RssNewsSource\] 수집 실패 — sourceId=|(UpbitNoticeSource|CryptoPanicSource|CoinGeckoTrendingSource)\] 수집 실패: )'
NEWSCFG="SELECT source_id || '|' || source_type || '|' || is_enabled || '|' || fetch_interval_min || '|' || coalesce(extract(epoch FROM last_fetched_at)::bigint::text, '-') FROM news_source_config ORDER BY source_id"
btcok() { [ "$(cut -d'|' -f1 "$1")" = true ] && [ "$(cut -d'|' -f4 "$1")" = 20/20 ] ; }
# BTC 관문은 candle 의 '기동 직전에도 판정 불가' 예외를 받지 않는다(v1.36 과 같다).
btcgate() { python3 - "$1" <<'PYEOF'
import re, sys
st = {}
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    m = re.search(r"\[캔들 최신성\] (.+?) 판정 (불가|재개) \(", line)
    if m and m.group(1).startswith("BTC 급락 관문 "):
        st[m.group(1)] = m.group(2)
if not st:
    print("BTC 관문: 판정 불가·재개 줄 없음")
for k, v in st.items():
    print("BTC 관문 마지막 상태: %s 판정 %s" % (k, v))
sys.exit(1 if "불가" in st.values() else 0)
PYEOF
}
# 시작 가능 시각(KST 10:10~20:59·22:10~07:59)과 직전 정기 보고 파일. 인자: 기준 epoch 초
kwin() { local now=$1 hm last
  hm=$(date -u -d "@$(( now + 32400 ))" +%H%M)
  if [ "$hm" -ge 1010 ] && [ "$hm" -lt 2100 ]; then last=$(date -u -d "@$(( now + 32400 ))" +%Y%m%d)-1000
  elif [ "$hm" -ge 2210 ]; then last=$(date -u -d "@$(( now + 32400 ))" +%Y%m%d)-2200
  elif [ "$hm" -lt 0800 ]; then last=$(date -u -d "@$(( now + 32400 - 86400 ))" +%Y%m%d)-2200
  else echo "시작 가능 시각 아님 — KST 10:10~20:59·22:10~07:59 에만 K0 (지금 KST ${hm:0:2}:${hm:2:2})"; return 1; fi
  [ -s "$HOME/daily-check/$last.txt" ] || { echo "직전 정기 보고 ~/daily-check/$last.txt 없음 — OPS §5 대로 보고만(재실행·재전송 금지)"; return 1; }
  echo "직전 정기 보고 ~/daily-check/$last.txt (읽은 뒤 시작)" ; }
# 뉴스 출처별 관측. 인자: 앱 로그, K0 설정, 지금 설정, 기동 epoch 초, 관측 epoch 초(지금 설정을 읽은 시각)
# 종료 1 = 설정(종류·사용·간격)이 K0 와 다름, 2 = 새 코드가 시도했는데 완료·미완료 줄 없음,
# 3 = ⚪ 계산상 회차가 관측 전에 지났는데 시도 기록·완료·미완료 줄 없음(확인 필요, 실패로 확정하지 않음)
newsobs() { python3 - "$@" <<'PYEOF'
import re, sys, time
if len(sys.argv) != 6:
    print("⚪ newsobs 인자는 5개(로그·K0 설정·지금 설정·기동 epoch·관측 epoch)"); sys.exit(2)
log, before, now, start, obs = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5])
def cfg(p):
    d = {}
    for line in open(p, encoding="utf-8"):
        f = line.rstrip("\n").split("|")
        if len(f) != 5:
            print("⚪ 뉴스 설정 줄 형식 다름: %r" % line); sys.exit(2)
        d[f[0]] = f
    return d
kst = lambda t: time.strftime("%H:%M KST", time.gmtime(t + 32400))
b, n = cfg(before), cfg(now)
seen = {}
pat = re.compile(r"\[NewsAggregator\] 수집 (완료|미완료) — sourceId=(\S+) (.*)$")
for line in open(log, encoding="utf-8", errors="replace"):
    m = pat.search(line)
    if m:
        seen.setdefault(m.group(2), []).append((m.group(1), m.group(3).strip()))
rc, chk = 0, 0
print("(기동 %s · 관측 %s · 회차는 기동 3분 뒤부터 15분 간격으로 계산 — 실제는 앞 회차 소요만큼 늦어질 수 있다)" % (kst(start), kst(obs)))
for k in sorted(set(b) | set(n)):
    if b.get(k, ["없음"])[:4] != n.get(k, ["없음"])[:4]:
        print("🔴 뉴스 설정이 K0 때와 다름: %s → %s" % ("|".join(b.get(k, ["없음"])[:4]), "|".join(n.get(k, ["없음"])[:4])))
        rc = 1
for k in sorted(n):
    sid, typ, en, iv, lf = n[k]
    rs = seen.get(sid, [])
    if rs:
        done = sum(1 for r in rs if r[0] == "완료")
        print("%s %s(%s분): 완료 %d·미완료 %d — 마지막 '수집 %s — %s'" % (sid, typ, iv, done, len(rs) - done, rs[-1][0], rs[-1][1]))
        for r in rs:
            if r[0] == "미완료" and ("종류=INVALID_RESPONSE" in r[1] or "종류=SOURCE_REPORTED_FAILURE" in r[1]):
                print("   🔴 새 판정 — 출처 원문과 함께 보고: 수집 미완료 — sourceId=%s %s" % (sid, r[1]))
    elif en not in ("t", "true"):
        print("%s %s: 사용 안 함" % (sid, typ))
    elif lf != "-" and int(lf) >= start + 60:
        print("⚪ %s %s: 새 코드가 시도(last_fetched_at %s)했는데 완료·미완료 줄 없음 — 원문 보고" % (sid, typ, kst(int(lf))))
        rc = max(rc, 2)
    else:
        due = max(int(lf) + int(iv) * 60 if lf != "-" else start, start + 180)
        tick = start + 180 + -(-(due - start - 180) // 900) * 900
        if obs < tick:
            print("%s %s(%s분): 미관측 — 계산상 차례 전(%s 이후 첫 회차 %s 무렵)" % (sid, typ, iv, kst(due), kst(tick)))
        else:
            print("⚪ %s %s(%s분): 확인 필요 — 계산상 회차 %s ≤ 관측 %s 인데 시도 기록·완료·미완료 줄 없음(앞 회차 소요로 늦을 수 있어 실패로 확정하지 않는다) — 보고" % (sid, typ, iv, kst(tick), kst(obs)))
            chk += 1
if chk:
    print("⚪ 확인 필요 %d개 — 정상 관측으로 세지 않는다. 수집 API·강제 재시도·주기 변경·즉시 되돌림 없이 출력만 보고" % chk)
sys.exit(rc if rc else (3 if chk else 0))
PYEOF
}
# N12 출처 HTTP 비200 줄 관측(v1.95). 인자: 앱 로그. 로그에서 읽은 sourceId·스레드·꼬리·본문은 출력하지 않는다(고정 출처 이름·상태·시각·건수만).
# 짝: 뒤 5줄 안 같은 스레드의 첫 줄이 같은 상태·같은 sourceId(CoinGecko 는 다른 출처가 찍지 않은 sourceId)의 상위 FAILED/HTTP_n/0건이어야 하고, 결과 줄 하나는 한 번만 쓴다.
# 종료 1 = 🔴 본문이 붙은 형식이거나 HTTP 줄 바로 뒤에 로그 머리 없는 연속 줄, 3 = ⚪ 고정 한 줄인데 짝 미확인, 0 = 모두 고정 한 줄+짝 또는 미관측
httpobs() { python3 - "$@" <<'PYEOF'
import re, sys
if len(sys.argv) != 2:
    print("⚪ httpobs 인자는 1개(앱 로그)"); sys.exit(3)
PRE = re.compile(r"^[A-Za-z0-9_.-]+-\d+\s+\| ?")
HEAD = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3}) \[([^\]]*)\]")
HTTP = re.compile(r"\[(CryptoPanicSource|UpbitNoticeSource|RssNewsSource|CoinGeckoTrendingSource)\] HTTP (\d{3})(.*)$")
SID = re.compile(r"^ — sourceId=(\S+)$")
UP = re.compile(r"\[NewsAggregator\] 수집 미완료 — sourceId=(\S+) 결과=FAILED 종류=HTTP_(\d{3}) 판정=\S+ 받은=0건 신규=0건$")
TAG = re.compile(r"\[(\w+Source)\] .*sourceId=([^\s:]+)")
# 로그에서 읽은 sourceId·스레드·꼬리·본문은 내부 비교에만 쓴다. 출력은 고정 출처 이름·상태 숫자·시각 숫자·건수뿐이다.
rows = [PRE.sub("", l.rstrip("\r\n")) for l in open(sys.argv[1], encoding="utf-8", errors="replace")]
heads = [(i, HEAD.match(r)) for i, r in enumerate(rows)]
heads = [(i, h) for i, h in heads if h]
owner = {}   # 로그의 sourceId → 그 sourceId 를 직접 찍은 출처 클래스들(CoinGecko 짝 확인용)
for r in rows:
    t = TAG.search(r)
    if t:
        owner.setdefault(t.group(2), set()).add(t.group(1))
bad, chk, seen, used = 0, 0, {}, set()
for n, (i, h) in enumerate(heads):
    r = rows[i]
    m = HTTP.search(r)
    if not m:
        continue
    src, st, tail = m.group(1), m.group(2), m.group(3)
    tm, th = h.group(1), h.group(2)
    cont = 0
    while i + 1 + cont < len(rows) and not HEAD.match(rows[i + 1 + cont]):
        cont += 1
    k = "%s HTTP %s" % (src, st)
    s = seen.setdefault(k, [0, 0, 0, 0, 0])   # 전체·고정 한 줄·본문 형식/연속 줄·상위 짝·짝 미확인
    s[0] += 1
    sm = SID.match(tail)
    fixed = (tail == "") if src == "CoinGeckoTrendingSource" else sm is not None
    if not fixed or cont:
        s[2] += 1; bad += 1
        print("🔴 %s %s HTTP %s — 고정 한 줄이 아님(꼬리 %d자·뒤 연속 줄 %d줄, 내용은 출력하지 않는다)" % (src, tm, st, len(tail), cont))
        continue
    s[1] += 1
    # 짝 후보: 뒤 5개 로그 머리 줄 안에서 같은 스레드의 첫 줄 하나뿐이다(출처 fetch 와 상위 결과는 같은 스레드에서 이어진다).
    # 그 줄이 다른 HTTP·다른 수집 결과·다른 기록이면 경계로 보고 더 뒤 줄을 쓰지 않는다. 결과 줄 하나는 한 번만 쓴다.
    j = None
    for i2, h2 in heads[n + 1:n + 6]:
        if h2.group(2) == th:
            j = i2; break
    why = None
    if j is None:
        why = "뒤 5줄 안에 같은 스레드의 다음 줄 없음"
    elif j in used:
        why = "같은 스레드 다음 줄이 이미 다른 HTTP 줄의 짝"
    else:
        u = UP.search(rows[j])
        if not u:
            why = "같은 스레드 다음 줄이 상위 FAILED/HTTP_n/0건 결과가 아님"
        elif u.group(2) != st:
            why = "같은 스레드 상위 결과의 상태가 다름"
        elif sm and u.group(1) != sm.group(1):
            why = "같은 스레드 상위 결과의 sourceId 가 HTTP 줄과 다름(값은 출력하지 않는다)"
        elif not sm and (owner.get(u.group(1), set()) - {src}):
            why = "같은 스레드 상위 결과의 sourceId 를 다른 출처가 찍음(값은 출력하지 않는다)"
    if why is None:
        used.add(j); s[3] += 1
    else:
        s[4] += 1; chk += 1
        print("⚪ %s %s HTTP %s — %s — 보고" % (src, tm, st, why))
if not seen:
    print("출처 HTTP 비200 줄: 미관측 — 이 구간에 자연 발생한 비200 응답 없음(실패를 유도하지 않는다)")
for k in sorted(seen):
    a, f, b, p, q = seen[k]
    print("%s ×%d — 고정 한 줄 %d · 본문 형식/연속 줄 %d · 같은 스레드 상위 FAILED/HTTP_n/0건 짝 %d · 짝 미확인 %d" % (k, a, f, b, p, q))
sys.exit(1 if bad else (3 if chk else 0))
PYEOF
}
# N13 HOLD 기준선 질의 관측(v1.101). 인자: 앱 로그. 출력은 시각·스레드 종류·앱 프레임(클래스.메서드)·건수만(연결 식별자·질의문·본문 없음).
# 누수 경고는 대여 60초 초과 때만 찍힌다(운영 leak-detection-threshold). HOLD 경고 = 첫 앱 프레임이 SignalQualityService.evaluateHoldBaselineLoop.
# 종료 1 = 🔴 P0: 연결 획득 시간 초과 줄 있음, 3 = ⚪ 장기 대여 경고 있음(HOLD 또는 그 밖 — 개선 미확인), 0 = 둘 다 없음
holdobs() { python3 - "$@" <<'PYEOF'
import re, sys
if len(sys.argv) != 2:
    print("⚪ holdobs 인자는 1개(앱 로그)"); sys.exit(3)
PRE = re.compile(r"^[A-Za-z0-9_.-]+-\d+\s+\| ?")
HEAD = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3}) \[([^\]]*)\]")
TRIG = re.compile(r"leak detection triggered for (\S+) on thread (\S+), stack trace follows")
UNL = re.compile(r"Previously reported leaked connection (\S+) on thread (\S+) was returned to the pool \(unleaked\)")
APP = re.compile(r"^\s+at com\.cryptoautotrader\.([\w.$]+)\([\w.]+:\d+\)")
TO = re.compile(r"Connection is not available, request timed out")
kind = lambda t: re.sub(r"-\d+$", "-N", t)
rows = [PRE.sub("", l.rstrip("\r\n")) for l in open(sys.argv[1], encoding="utf-8", errors="replace")]
leaks, cur, to, cs, ce = [], None, [], [], []
context = None
for r in rows:
    h = HEAD.match(r)
    if h:
        context = (h.group(1), kind(h.group(2)))
    # Hikari timeout can be on a non-header Caused by line. Count every matching line once.
    if TO.search(r):
        to.append(context or ("시각미확인", "스레드미확인"))
    if h:
        cur = None
        m = TRIG.search(r)
        if m:
            cur = {"t": h.group(1), "c": m.group(1), "th": m.group(2), "f": None, "ret": None}
            leaks.append(cur)
            continue
        m = UNL.search(r)
        if m:
            for k in reversed(leaks):
                if k["c"] == m.group(1) and k["th"] == m.group(2) and k["ret"] is None:
                    k["ret"] = h.group(1)
                    break
            continue
        if "[SignalQuality] 시작 Catchup 시작" in r:
            cs.append(h.group(1))
        if "[SignalQuality] 시작 Catchup 완료" in r:
            ce.append(h.group(1))
        continue
    if cur is not None and cur["f"] is None:
        m = APP.match(r)
        if m:
            cur["f"] = m.group(1)
HOLDF = "api.service.SignalQualityService.evaluateHoldBaselineLoop"
hold = [k for k in leaks if k["f"] == HOLDF]
other = [k for k in leaks if k["f"] != HOLDF]
print("HOLD 기준선 질의 장기 대여 경고 %d건(반환 %d·반환 미관측 %d) · 그 밖의 장기 대여 경고 %d건 · 연결 획득 시간 초과 줄 %d건" % (
    len(hold), sum(1 for k in hold if k["ret"]), sum(1 for k in hold if not k["ret"]), len(other), len(to)))
for k in hold:
    print("  · HOLD %s %s · 반환 %s" % (k["t"], kind(k["th"]), k["ret"] or "미관측"))
for k in other:
    print("  · 그 밖 %s %s · %s · 반환 %s" % (k["t"], kind(k["th"]), k["f"] or "(앱 프레임 없음)", k["ret"] or "미관측"))
for t, th in to:
    print("  · 🔴 연결 획득 시간 초과 %s %s" % (t, th))
print("시작 Catchup: 시작 %s · 완료 %s (로그 머리 시각 UTC)" % (",".join(cs) or "없음", ",".join(ce) or "없음"))
sys.exit(1 if to else (3 if leaks else 0))
PYEOF
}
# N15·N16 줄 관측(v1.111). 인자: 앱 로그. 출력은 로그 머리 시각·엔진 종류·건수만(코인·세션 id·가격·사유 본문은 내지 않는다).
# 자연 발생만 본다 — 가격 이상·만료를 유도하지 않는다. 0건이면 "미관측" 이고 실패가 아니다.
# 종료 1 = 🔴 만료 사유 기록 실패 줄 있음(원래 신호에 사유가 남지 않았을 수 있다), 3 = ⚪ 인자 오류, 0 = 그 밖(관측·미관측 모두)
entryobs() { python3 - "$@" <<'PYEOF'
import re, sys
if len(sys.argv) != 2:
    print("⚪ entryobs 인자는 1개(앱 로그)"); sys.exit(3)
PRE = re.compile(r"^[A-Za-z0-9_.-]+-\d+\s+\| ?")
HEAD = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3}) \[([^\]]*)\]")
CAT = [
    ("콜백 전 만료", re.compile(r"\[진입 우선순위\] \d+위 \S+ (LIVE|DYNAMIC)#\d+ → 승인 실행 만료\(")),
    ("의도 직전 만료", re.compile(r"의도 저장 직전 승인 실행 만료 — 매수 의도를 만들지 않는다 \(sessionKind=(LIVE|DYNAMIC),")),
    ("예약·의도 경계 거절", re.compile(r"(매수 예약이 거절돼 롤백했다|\[Dynamic\] 매수 예약 거절) \(.*사유=ENTRY_EXPIRED\)")),
    ("만료 사유 기록 실패", re.compile(r"\[진입 우선순위\] 만료 사유 기록 실패")),
    ("가격 아님 무시", re.compile(r"(\[Dynamic\] )?가격이 아닌 실시간 시세 무시: ")),
]
def kind(name, m, line):
    if name == "예약·의도 경계 거절":
        return "DYNAMIC" if m.group(1).startswith("[Dynamic]") else "LIVE"
    if name == "가격 아님 무시":
        return "DYNAMIC" if m.group(1) else "LIVE"
    if name == "만료 사유 기록 실패":
        return "-"
    return m.group(1)
seen = {c[0]: [] for c in CAT}
for raw in open(sys.argv[1], encoding="utf-8", errors="replace"):
    r = PRE.sub("", raw.rstrip("\r\n"))
    h = HEAD.match(r)
    if not h:
        continue
    for name, pat in CAT:
        m = pat.search(r)
        if m:
            seen[name].append((h.group(1), kind(name, m, r)))
            break
cnt = lambda n, k: sum(1 for _, x in seen[n] if x == k)
n15 = sum(len(seen[n]) for n in ("콜백 전 만료", "의도 직전 만료", "예약·의도 경계 거절", "만료 사유 기록 실패"))
print("N15 승인 실행 만료 — 콜백 전 LIVE %d·DYNAMIC %d · 예약·의도 경계 거절(ENTRY_EXPIRED) LIVE %d·DYNAMIC %d(그중 의도 직전 LIVE %d·DYNAMIC %d) · 만료 사유 기록 실패 %d" % (
    cnt("콜백 전 만료", "LIVE"), cnt("콜백 전 만료", "DYNAMIC"), cnt("예약·의도 경계 거절", "LIVE"), cnt("예약·의도 경계 거절", "DYNAMIC"),
    cnt("의도 직전 만료", "LIVE"), cnt("의도 직전 만료", "DYNAMIC"), len(seen["만료 사유 기록 실패"])))
print("N16 가격이 아닌 실시간 시세 무시 — LIVE %d·DYNAMIC %d" % (cnt("가격 아님 무시", "LIVE"), cnt("가격 아님 무시", "DYNAMIC")))
for name, _ in CAT:
    for t, k in seen[name][:20]:
        print("  · %s%s %s %s" % ("🔴 " if name == "만료 사유 기록 실패" else "", name, t, k))
    if len(seen[name]) > 20:
        print("  · %s 그 밖 %d건(목록 생략)" % (name, len(seen[name]) - 20))
if not n15:
    print("N15: 미관측 — 이 구간에 자연 발생한 승인 실행 만료 없음(유도하지 않는다)")
if not seen["가격 아님 무시"]:
    print("N16: 미관측 — 이 구간에 자연 발생한 가격 아닌 실시간 시세 없음(유도하지 않는다)")
sys.exit(1 if seen["만료 사유 기록 실패"] else 0)
PYEOF
}
# N17 줄 관측(v1.119). 인자: 앱 로그. 출력은 로그 머리 시각·엔진 종류·건수만(코인·가격은 내지 않는다).
# 자연 발생만 본다 — REST 가격 이상을 유도하지 않는다. 0건이면 "미관측" 이고 실패가 아니다. 종료 3 = ⚪ 인자 오류, 0 = 그 밖
n17obs() { python3 - "$@" <<'PYEOF'
import re, sys
if len(sys.argv) != 2:
    print("⚪ n17obs 인자는 1개(앱 로그)"); sys.exit(3)
PRE = re.compile(r"^[A-Za-z0-9_.-]+-\d+\s+\| ?")
HEAD = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3}) \[([^\]]*)\]")
PAT = re.compile(r"\[(Dynamic\]\[)?§9\] REST 강제 갱신 시세가 가격이 아님 . 발행하지 않음 \(")   # 가운데 한 글자(—)는 콘솔 인코딩에 따라 ? 로 찍힐 수 있다
seen = []
for raw in open(sys.argv[1], encoding="utf-8", errors="replace"):
    r = PRE.sub("", raw.rstrip("\r\n"))
    h = HEAD.match(r)
    if not h:
        continue
    m = PAT.search(r)
    if m:
        seen.append((h.group(1), "DYNAMIC" if m.group(1) else "LIVE"))
print("N17 REST 강제 갱신 가격 아님(INVALID_PRICE·미발행) — LIVE %d·DYNAMIC %d" % (
    sum(1 for _, k in seen if k == "LIVE"), sum(1 for _, k in seen if k == "DYNAMIC")))
for t, k in seen[:20]:
    print("  · %s %s" % (t, k))
if len(seen) > 20:
    print("  · 그 밖 %d건(목록 생략)" % (len(seen) - 20))
if not seen:
    print("N17: 미관측 — 이 구간에 자연 발생한 REST 강제 갱신 가격 이상 없음(유도하지 않는다)")
sys.exit(0)
PYEOF
}
# N19 줄 관측(v1.124 — ee509cf 고정 문구). 인자: 앱 로그. 출력은 로그 머리 시각·사유·건수만(코인·값은 내지 않는다 — 문구에도 없다).
# 자연 발생만 본다 — WS 를 끊거나 REST 이상 응답을 유도하지 않는다. 0건이면 "미관측" 이고 실패가 아니다. 종료 3 = ⚪ 인자 오류, 0 = 그 밖
n19obs() { python3 - "$@" <<'PYEOF'
import re, sys
if len(sys.argv) != 2:
    print("⚪ n19obs 인자는 1개(앱 로그)"); sys.exit(3)
PRE = re.compile(r"^[A-Za-z0-9_.-]+-\d+\s+\| ?")
HEAD = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3}) \[([^\]]*)\]")
PAT = re.compile(r"\[§9\] REST 폴백 시세 항목 건너뜀 . (가격 형식이 숫자가 아님|가격이 아님 \(부호=(?:0|음수)\)|코인 형식이 문자열이 아님)\s*$")   # 가운데 한 글자(—)는 콘솔 인코딩에 따라 ? 로 찍힐 수 있다
KIND = {"가격 형식": "형식", "가격이 아": "가격 아님", "코인 형식": "코인 형식"}
seen = []
for raw in open(sys.argv[1], encoding="utf-8", errors="replace"):
    r = PRE.sub("", raw.rstrip("\r\n"))
    h = HEAD.match(r)
    if not h:
        continue
    m = PAT.search(r)
    if m:
        seen.append((h.group(1), KIND[m.group(1)[:5]]))
c = lambda k: sum(1 for _, x in seen if x == k)
print("N19 REST 전역 폴백 항목 건너뜀 — 형식 이상 %d·가격 아님 %d·코인 형식 %d" % (c("형식"), c("가격 아님"), c("코인 형식")))
for t, k in seen[:20]:
    print("  · %s %s" % (t, k))
if len(seen) > 20:
    print("  · 그 밖 %d건(목록 생략)" % (len(seen) - 20))
if not seen:
    print("N19: 미관측 — 이 구간에 자연 발생한 REST 전역 폴백 이상 항목 없음(유도하지 않는다)")
sys.exit(0)
PYEOF
}
# N20 줄 관측(§5-9 98 — 1899b49 고정 문구). 인자: 앱 로그. 출력은 로그 머리 시각·사유·건수만(코인·값은 내지 않는다 — 문구에도 없다).
# 자연 발생만 본다 — 이상 응답·예외를 유도하지 않는다. 0건이면 "미관측" 이고 실패가 아니다. 종료 3 = ⚪ 인자 오류, 0 = 그 밖
n20obs() { python3 - "$@" <<'PYEOF'
import re, sys
if len(sys.argv) != 2:
    print("⚪ n20obs 인자는 1개(앱 로그)"); sys.exit(3)
PRE = re.compile(r"^[A-Za-z0-9_.-]+-\d+\s+\| ?")
HEAD = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3}) \[([^\]]*)\]")
PATS = [   # 가운데 한 글자(—)는 콘솔 인코딩에 따라 ? 로 찍힐 수 있다
    ("항목 형식", re.compile(r"\[시세 보강\] 시세 항목 건너뜀 . 가격 형식이 숫자가 아님\s*$")),
    ("항목 가격 아님", re.compile(r"\[시세 보강\] 시세 항목 건너뜀 . 가격이 아님 \(부호=(?:0|음수)\)\s*$")),
    ("항목 코인 형식", re.compile(r"\[시세 보강\] 시세 항목 건너뜀 . 코인 형식이 문자열이 아님\s*$")),
    ("시세 처리 실패", re.compile(r"\[시세 보강\] 시세 처리 실패 \(종류=\w+\)\s*$")),
    ("회차 오류", re.compile(r"\[시세 보강\] 회차 오류 \(종류=\w+\)\s*$")),
    ("경보 전송 실패", re.compile(r"\[시세 보강\] 경보 전송 실패 \(종류=\w+\)\s*$")),
    ("호가 보강", re.compile(r"\[시세 보강\] 호가로 보강한 보유 ")),
]
seen = []
for raw in open(sys.argv[1], encoding="utf-8", errors="replace"):
    r = PRE.sub("", raw.rstrip("\r\n"))
    h = HEAD.match(r)
    if not h:
        continue
    for k, p in PATS:
        if p.search(r):
            seen.append((h.group(1), k))
            break
c = lambda k: sum(1 for _, x in seen if x == k)
print("N20 보유 시세 보강 — " + "·".join("%s %d" % (k, c(k)) for k, _ in PATS))
for t, k in seen[:20]:
    print("  · %s %s" % (t, k))
if len(seen) > 20:
    print("  · 그 밖 %d건(목록 생략)" % (len(seen) - 20))
if not any(k != "호가 보강" for _, k in seen):
    print("N20: 미관측 — 이 구간에 자연 발생한 시세 보강 항목 건너뜀·처리 실패·회차 오류·경보 전송 실패 없음(유도하지 않는다)")
sys.exit(0)
PYEOF
}
# ── 감시 스크립트 고정값 — 운영 0e1d2a02 과 후보 d81c3f2f 의 start_66.py·시험 파일은 같은 블롭이다(D4 는 감시 스크립트를 바꾸지 않는다)
NF=scripts/prospective/start_66.py; NT=scripts/prospective/test_freshness_recheck_fail.py   # NT 는 새로 추적되는 시험 파일 — 서버에서 실행하지 않는다
NBOLD=6305e512f88e0e9e6f0ec32ec99a28c48bc3fc8d; NBNEW=6305e512f88e0e9e6f0ec32ec99a28c48bc3fc8d   # start_66.py 블롭(운영 0e1d2a02 · 후보 d81c3f2f — 같다, mkd81.py 확인)
NHOLD=68d441ce0ed66d0a654d9ec7ca485d0a69a5bb8bacf4de48b5d61d0ef813b56b; NHNEW=68d441ce0ed66d0a654d9ec7ca485d0a69a5bb8bacf4de48b5d61d0ef813b56b   # 파일 sha256
# ── N21 공통 함수 시작(통합 초안 D3 U변수에 같은 원문으로 들어간다) ──
JLOG=scripts/prospective/freshness_log.jsonl      # 관측 행(회차마다 한 행)
RUNLOG=scripts/prospective/freshness_runs.log     # cron 회차의 표준 출력·오류(>> 덧붙임)
SYSLOG=/var/log/syslog                            # cron 시작 줄(CRON[pid]: (사용자) CMD (…))
# freshness 회차 프로세스 패턴 — 명령줄 맨 앞이 python 실행 파일인 프로세스만 잡는다.
# cron 의 /bin/sh -c 껍데기, 이 블록을 담은 bash(대화형·bash -c·bash 파일) 명령줄은 맨 앞이 python 이 아니라서 잡히지 않는다.
FPAT='^[^ ]*python[0-9.]* +([^ ]+ +)*[^ ]*start_66[.]py +freshness'
# 회차 조회. 인자: 기록 이름. 종료 0 = 회차 없음(pgrep 종료 1·출력 없음), 1 = 회차 있음(pgrep 종료 0), 2 = 조회 실패(그 밖 전부)
fresh_idle() { local rc=0
  pgrep -af "$FPAT" > "$M/pgrep-$1.txt" 2> "$M/pgrep-$1.err" || rc=$?
  case "$rc" in
    1) if [ -s "$M/pgrep-$1.txt" ] || [ -s "$M/pgrep-$1.err" ]; then cat "$M/pgrep-$1.txt" "$M/pgrep-$1.err"; echo "프로세스 조회 이상: pgrep 종료 1 인데 출력 있음"; return 2; fi
       echo "freshness 회차 없음(pgrep 종료 1)"; return 0 ;;
    0) cat "$M/pgrep-$1.txt"; echo "freshness 회차가 돌고 있다(pgrep 종료 0)"; return 1 ;;
    *) cat "$M/pgrep-$1.txt" "$M/pgrep-$1.err"; echo "프로세스 조회 실패(pgrep 종료 $rc)"; return 2 ;;
  esac; }
# 적용 직전 기준 위치. 인자: 표지 이름. 시스템 기록 inode·줄 수, 실행 기록 줄 수, 관측 행 수. 하나라도 못 읽으면 종료 1
n21mark() {
  [ -r "$SYSLOG" ] || { echo "시스템 기록 $SYSLOG 를 읽을 수 없음"; return 1; }
  [ -f "$RUNLOG" ] && [ -r "$RUNLOG" ] || { echo "실행 기록 $RUNLOG 를 읽을 수 없음"; return 1; }
  [ -f "$JLOG" ] && [ -r "$JLOG" ] || { echo "관측 행 $JLOG 를 읽을 수 없음"; return 1; }
  printf '%s %s %s %s\n' "$(stat -c %i "$SYSLOG")" "$(wc -l < "$SYSLOG")" "$(wc -l < "$RUNLOG")" "$(wc -l < "$JLOG")" > "$M/mark-$1.txt"
  echo "기준 위치(시스템 기록 inode·줄, 실행 기록 줄, 관측 행): $(cat "$M/mark-$1.txt")"; }
# 적용 뒤 확인. 인자: 표지 이름. 구간 = 표지 위치 뒤에 덧붙은 줄. 종료 0 = 세 근거 모두 확인, 1 = 미확인 또는 이상(이유 출력)
# PID·완료 식별자가 없어 시작 줄과 관측 행의 1:1 짝은 확정하지 않는다. at 은 재확인 회차면 재확인 뒤, 아니면 1차 조회 전 시각이다
n21after() { local ino s0 r0 j0 ino1 s1 r1 j1 bad=0
  read -r ino s0 r0 j0 < "$M/mark-$1.txt" || { echo "미확인: 기준 위치 기록 없음"; return 1; }
  [ -r "$SYSLOG" ] || { echo "미확인: 시스템 기록 $SYSLOG 를 읽을 수 없음"; return 1; }
  [ -f "$RUNLOG" ] && [ -r "$RUNLOG" ] && [ -f "$JLOG" ] && [ -r "$JLOG" ] || { echo "미확인: 실행 기록·관측 행 파일을 읽을 수 없음"; return 1; }
  ino1=$(stat -c %i "$SYSLOG"); s1=$(wc -l < "$SYSLOG"); r1=$(wc -l < "$RUNLOG"); j1=$(wc -l < "$JLOG")
  echo "기준 → 지금: 시스템 기록 inode $ino→$ino1 줄 $s0→$s1 · 실행 기록 줄 $r0→$r1 · 관측 행 $j0→$j1"
  [ "$ino1" = "$ino" ] && [ "$s1" -ge "$s0" ] || { echo "미확인: 시스템 기록이 교체·축소됨(회전 등) — 회전된 파일을 찾지 않고 보고"; return 1; }
  [ "$r1" -ge "$r0" ] && [ "$j1" -ge "$j0" ] || { echo "미확인: 실행 기록 또는 관측 행이 줄었다 — 보고"; return 1; }
  sed -n "$((s0 + 1)),${s1}p" "$SYSLOG" | grep -E 'CRON\[[0-9]+\]: (\([^)]*\) )?CMD \(.*start_66[.]py freshness' > "$M/cron-after-$1.txt" || true
  sed -n "$((r0 + 1)),${r1}p" "$RUNLOG" > "$M/runlog-after-$1.txt"
  sed -n "$((j0 + 1)),${j1}p" "$JLOG" > "$M/log-after-$1.jsonl"
  echo "== 적용 뒤 cron 시작 줄 $(wc -l < "$M/cron-after-$1.txt")개"; tail -3 "$M/cron-after-$1.txt"
  echo "== 적용 뒤 실행 기록 $(wc -l < "$M/runlog-after-$1.txt")줄 · 그중 Traceback $(grep -c 'Traceback' "$M/runlog-after-$1.txt" || true)줄"
  echo "== 적용 뒤 관측 행 $(wc -l < "$M/log-after-$1.jsonl")개"
  [ -s "$M/cron-after-$1.txt" ] || { echo "미확인: 적용 뒤 cron 시작 줄이 없다 — 새 판이 cron 으로 시작됐다는 근거 없음"; bad=1; }
  [ -s "$M/log-after-$1.jsonl" ] || { echo "미확인: 적용 뒤 새 관측 행이 없다 — 회차가 아직 안 끝났거나 실패(손으로 돌리지 않는다)"; bad=1; }
  if grep -q 'Traceback' "$M/runlog-after-$1.txt"; then grep -n -A3 'Traceback' "$M/runlog-after-$1.txt" | tail -20; echo "이상: 적용 뒤 실행 기록에 새 Traceback"; bad=1; fi
  local py=0; [ ! -s "$M/log-after-$1.jsonl" ] || python3 -c '
import json, sys
sys.stdout.reconfigure(encoding="utf-8")
keys = ["at", "green", "rechecked", "red", "redCoins", "rows", "unknown", "unknownCoins"]
rk = ["count", "dbTo", "exErr", "exTo", "rechecked"]
bad = 0
for line in open(sys.argv[1], encoding="utf-8"):
    try:
        r = json.loads(line)
        ok = sorted(r) == keys and all(sorted(v) == rk for v in r["rows"].values())
        stale = [p for p, v in r["rows"].items() if v["exErr"] and v["exTo"]]
    except Exception as e:
        print("행 해석 실패:", type(e).__name__); bad += 1; continue
    print(r["at"], "🟢%d ⚪%d 🔴%d 재확인 %d" % (r["green"], r["unknown"], r["red"], len(r["rechecked"])),
          "칸 같음" if ok else "칸 다름", "N21 상태 %d" % len(stale))
    bad += (not ok) or bool(stale)
sys.exit(1 if bad else 0)
' "$M/log-after-$1.jsonl" > "$M/rows-after-$1.txt" 2>&1 || py=$?
  [ ! -s "$M/rows-after-$1.txt" ] || cat "$M/rows-after-$1.txt"
  [ "$py" = 0 ] || { echo "이상: JSON 칸이 다르거나 N21 상태 행(실패 종류가 있는데 exTo 가 남음)·해석 실패(종료 $py)"; bad=1; }
  return "$bad"; }
# ── N21 공통 함수 끝 ──
