from pathlib import Path
import sys,unittest,importlib.util
OUT=Path(__file__).resolve().parent
sys.path.insert(0,str(OUT/'snapshot/ab-create-v2-20261111/tool'))
import test_ab_aggregate
with (OUT/'aggregate-tests.txt').open('w',encoding='utf8') as f:
    result=unittest.TextTestRunner(stream=f,verbosity=2).run(unittest.defaultTestLoader.loadTestsFromModule(test_ab_aggregate))
print('aggregate',result.testsRun,'success',result.wasSuccessful(),'skipped',len(result.skipped))
raise SystemExit(0 if result.wasSuccessful() else 1)
