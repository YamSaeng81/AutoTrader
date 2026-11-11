import shutil,subprocess,sys,tempfile,json
from pathlib import Path
SRC=Path(sys.argv[1])
M=[
 ("M1 API 대조 성공 조건 무력화","create/ab_create.py",'    if rec.get("status") != "조회됨":\n        return [rec.get("status") or "목록 대조 결과 없음"]','    return []'),
 ("M2 결과 없는 의도 검사 무력화","create/ab_create.py",'    unresolved = sorted(k for k, n in intents.items() if n > results.get(k, 0))','    unresolved = []'),
 ("M3 요청 직전 창 재확인 무력화","create/ab_create.py",'        if c_k != c or not inside_k:','        if False:'),
 ("M4 db_verified 검사 무력화","tool/ab_aggregate.py",'    if creation_log.get("db_verified") is not True:','    if False:'),
 ("M5 지문 형식 검사 무력화","tool/ab_aggregate.py",'HASH_RE = re.compile(r"^[0-9a-f]{12}$")','HASH_RE = re.compile(r".*")'),
 ("M6 포지션 상태 계약 무력화","tool/ab_aggregate.py",'    check_positions(pos_rows, ids)\n','\n'),
 ("M7 완성기 팔 뒤바뀜 검사 무력화","create/ab_complete.py",'        if arm_of(r.get("stop_loss_pct")) != s["arm"]:','        if False:'),
 ("M8 잠금 무력화","create/ab_create.py",'    if not acquire_lock(out):','    if False:'),
]
res=[]
for name,rel,a,b in M:
    d=Path(tempfile.mkdtemp(prefix="mut-")); shutil.copytree(SRC,d/"v3")
    f=d/"v3"/rel; s=f.read_text(encoding="utf-8"); assert s.count(a)==1,(name,s.count(a)); f.write_text(s.replace(a,b),encoding="utf-8")
    fails=0; ran=0
    for folder,mods in (("tool",["test_ab_aggregate"]),("create",["test_ab_create","test_chain"])):
        for m in mods:
            p=subprocess.run([sys.executable,"-X","utf8","-W","ignore","-m","unittest",m],cwd=d/"v3"/folder,capture_output=True,text=True,encoding="utf-8")
            line=[l for l in p.stderr.splitlines() if l.startswith("FAILED") or l=="OK"]
            if p.returncode!=0: fails+=1
    res.append({"mutation":name,"killed":fails>0,"failing_suites":fails})
    shutil.rmtree(d,ignore_errors=True)
print(json.dumps(res,ensure_ascii=False,indent=1))
