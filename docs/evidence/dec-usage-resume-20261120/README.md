# 사용량 관측·80% 대기·재개 수단 확인 (총괄 v1.210 §3, §2 순서1)

2026-11-20 16:59~17:10 KST, 개발 세션(VS Code 확장 패널, CLI 2.1.327). 실제 한도를 소진하는 시험은 하지 않았다. 프로젝트 권한·운영 설정은 바꾸지 않았다.

| 항목 | 결과 | 근거 |
|---|---|---|
| 자동 계속 `autoContinueAtUsageLimit` | **유효값 true(기본값)**. 사용자 설정 미설정, 관리 설정 없음(레지스트리 정책 키 없음, ProgramData 경로 없음), 프로젝트·local 파일에도 키 없음(있으면 오히려 꺼짐). 설정 변경 불필요. VS Code 패널 적용 여부는 문서에 명시 없음 → 실제 동작 미확인 | 공식 settings-reference(기본 true, 2.1.234 이상, 사용자/관리 범위) |
| 사용률 관측(status line) | 사용자 설정에 `statusLine` 추가(백업 `settings.json.bak-20261120-statusline`). 스크립트 `~/.claude/statusline-usage.js`는 `rate_limits.five_hour/seven_day`의 `used_percentage`·`resets_at`만 `~/.claude/usage-observed.json`에 기록. 샘플 입력으로 동작 확인. **이 VS Code 패널 세션에서는 여러 응답 뒤에도 파일이 생기지 않음 → 사용률 미확인(0 아님)** | 공식 statusline 문서(VS Code 언급 없음) |
| 일회 예약 | `CronCreate` 사용 가능. 로컬 시간대 KST(GMT+0900) 확인. N03 22:00 회차 회수를 **ID d95c7a84, 11-20 22:14 KST 1회** 예약(세션 전용, 세션 종료 시 소멸) | CronCreate 응답 |
| 80% 재개 예약 | 사용률을 읽을 수 없으므로 80% 도달을 스스로 판정할 수 없다. 사용률 미확인 상태를 80% 미만으로 선언하지 않는다 | — |

## 운영자 확인 요청

- 터미널 CLI(`claude`)로 이 세션을 이어 가면 status line이 동작해 사용률이 파일에 남는다. 또는 `/usage`로 직접 확인한 값을 알려 주면 기록한다.
- PC 절전·VS Code 종료 시 예약 d95c7a84와 이후 예약은 사라진다(자동 재개 미확인으로 기록).
