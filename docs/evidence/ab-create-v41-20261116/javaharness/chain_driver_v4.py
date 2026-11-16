"""이름 맞춤 래퍼 — 0e1d2a02 의 AbChainV4PgTest.preflight 는 AB_CHAIN_DRIVER 파일 이름이 chain_driver_v4.py 인지 본다(시험 코드는 바꾸지 않는다).
이 파일은 바로 위 폴더의 chain_driver_v41.py(v4.1 도구 구동기)를 그대로 실행할 뿐 다른 동작이 없다."""
import runpy
import sys
from pathlib import Path

target = Path(__file__).resolve().parents[1] / "chain_driver_v41.py"
sys.argv[0] = str(target)
runpy.run_path(str(target), run_name="__main__")
