# B WF(#4) 로컬 실행 보존본 — 2026-10-28

리뷰 9차 판정에 따라 보존한다. 바이트를 바꾸지 않도록 `.gitattributes` 에서 줄 끝 변환을 끈다.

| 파일 | SHA-256 | 내용 |
|---|---|---|
| `bwf_result.csv` | `155d45e430fb7406a96fadcd4df4d4f0a2d9e155873a936ca53c0e9e0b98f97e` | 코인별 결과(원본 `d:/tmp/bwf_out/bwf_result.csv` 와 같다) |
| `bwf_run_cp949.log` | `95eaaeb67bf0f67eaa28c700eb10da48fc8b3bd5b40d0f3ede449be79357a90e` | Gradle `-i` 실행 로그 원본. 한글은 cp949(테스트 JVM `file.encoding=x-windows-949`) |

- 러너: `core-engine/src/test/java/com/cryptoautotrader/core/backtest/BWalkForwardRunner.java`, 실행 코드 `27eed13`.
- 입력: 청산 규칙(운영 `risk_config` ID 1 원값 5.00·2.00·t·0.500·0.300·80.00), 최대 보유 24, BTC `d:/tmp/stval_btc/btc_h1.csv`(SHA-256 `c724082e…4b2b`), 코인 CSV `d:/tmp/stval/st_*_h1.csv`(BLAST 제외 21개).
- 결과: 사전 등록 충족 0/21 · 코드 게이트 PASS 0/21(OVERFITTING 20 · INSUFFICIENT_DATA 1).
- 판정·결정: `docs/LIVE_READINESS.md` §1 #4, `docs/B_VALIDATION_PREREG.md` 상태·§9.
