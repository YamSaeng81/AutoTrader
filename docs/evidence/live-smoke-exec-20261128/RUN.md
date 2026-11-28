# LIVE#204 실제 서버 시험 진행 기록

사용자 회신: 휴대폰 수신/대응 가능, APENFT는 기존 보유로 분리해 진행. 시험 중 외부 수동 거래/입출금 중지 조건을 반영했다. APENFT/SID203은 변경하지 않는다.

- 초기 stage SSH255와 MaxStartups 진단은 `ssh-stage-failure.json`에 있다.55초 뒤 읽기13:55:31에 전용 폴더 부재를 확인하고 `start_job.py`라는 새 원문으로 한 번 실행했다. 초기 stage를 무조건 재전송하거나 제한을 낮추지 않았다.
- 서버 작업 시작 **14:00:59 KST**, 경로 `~/work-batches/live-smoke-20261128-exp1`, driver PID249945. runtime `20261128-1400`, deadline **14:20:59 KST**다. 서버 안의 새 원문8개는 `run-package-manifest.json`과 전부 일치한다. 기존 `package-manifest.json`은 실패한 최초 stage4개용으로 현재 실행 패키지가 아니다.
- LIVE **#204** 생성, 원금10,000·비율0.995·상한9,950·DAILY_SMA20/BTC/H1·손절5·보유시간0·maxEntries정수1을 응답과 DB로 대조했다. 관측기 PID250633의 armed를 확인한 뒤 start1회를 보냈다.
- 14:01:26/14:03:00 직접 파일 읽기에서 phase OBSERVING·세션 RUNNING·매수 의도0·보유0·미결0·driver/observer error 없음. 기록은 `status-*.json`이다. 이 상태를 체결 성공으로 세지 않는다.
- 실제 시작 전에 서버 driver가 정확 잔고/수수료/배정·기존 보유를 새로 읽고 통과했다. 시작 직전 값과 P0·생성/검증/시작 출력은 서버 driver-output/ 및 ~/smoke-20261128-1400에 보존한다. 원자적 거래소/DB 스냅숏 또는 전체P7 증명으로 확대하지 않는다.
- 자연 신호만 사용한다. 매수1회 정산 또는 deadline에 관측기가 정지1회와 기존 gate v4/거래소 BTC 잔량 확인을 실행한다. 정지와 주문이 경합하거나 정지 응답이 불명인 경우 재전송하지 않고 실제 결과를 보고한다. 서버 재부팅 시 자동 재개하지 않는다.

최종 완료/실체결 여부는 final·gate·거래소 잔량 원문을 회수한 뒤 추가한다. 현재는 진행 중이다. 전체 제품/PG/회귀/jar 시험을 반복하지 않았다.
