"""격리 로컬 PG 준비(총괄 v1.205 §4-2, R3 추가 1묶음). 운영·제품 PG·애플리케이션은 쓰지 않는다.
네트워크 없는 일회용 컨테이너(--network none, 데이터 tmpfs)에 d81c3f2f 마이그레이션 V1~V96 을 DB 'base' 에 적용한다.
flyway_schema_history 는 (version, success) 최소 표로 대신한다(제품 Flyway validate 아님).
사용: python -I setup_pg.py --container <이름> --out <회차 폴더>   (끝난 뒤 docker rm -f <이름>)
"""
import argparse, os, re, subprocess, sys, tempfile, time

IMAGE = 'timescale/timescaledb:latest-pg15'
SRC = 'd81c3f2f'
MIG = 'web-api/src/main/resources/db/migration'


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, **kw)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--container', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=False)
    log = []
    r = sh(['docker', 'run', '-d', '--name', a.container, '--network', 'none', '--tmpfs', '/var/lib/postgresql/data',
            '-e', 'POSTGRES_PASSWORD=x', IMAGE])
    if r.returncode:
        raise SystemExit(f'컨테이너 실패 {r.stderr!r}')
    for _ in range(60):
        if sh(['docker', 'exec', a.container, 'pg_isready', '-U', 'postgres']).returncode == 0 and \
           sh(['docker', 'exec', a.container, 'psql', '-U', 'postgres', '-tAc', 'select 1']).returncode == 0:
            break
        time.sleep(1)
    time.sleep(3)
    ps = lambda db, args, stdin=None: sh(['docker', 'exec', '-i', a.container, 'psql', '-X', '-q', '-U', 'postgres', '-d', db,
                                          '-v', 'ON_ERROR_STOP=1'] + args, input=stdin)
    r = ps('postgres', ['-c', 'CREATE DATABASE base'])
    if r.returncode:
        raise SystemExit(f'base 실패 {r.stderr!r}')
    files = sh(['git', 'ls-tree', '--name-only', f'{SRC}:{MIG}']).stdout.decode().split()
    vers = sorted((int(m.group(1)), f) for f in files if (m := re.match(r'V(\d+)__.*\.sql$', f)))
    for v, f in vers:
        body = sh(['git', 'show', f'{SRC}:{MIG}/{f}']).stdout
        r = ps('base', [], stdin=body)
        if r.returncode:
            raise SystemExit(f'{f} 적용 실패 {r.stderr!r}')
    ins = 'CREATE TABLE flyway_schema_history(version text, success boolean);' + \
          'INSERT INTO flyway_schema_history VALUES ' + ','.join(f"('{v}',true)" for v, _ in vers) + ';'
    r = ps('base', ['-c', ins])
    if r.returncode:
        raise SystemExit(f'flyway 표 실패 {r.stderr!r}')
    r = ps('postgres', ['-c', "ALTER DATABASE base ALLOW_CONNECTIONS false; "
                              "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='base';"])
    ver = sh(['docker', 'exec', a.container, 'psql', '-U', 'postgres', '-tAc', 'select version()']).stdout.decode().strip()
    net = sh(['docker', 'inspect', '-f', '{{.HostConfig.NetworkMode}} {{json .HostConfig.Tmpfs}}', a.container]).stdout.decode().strip()
    log += [f'image={IMAGE}', f'server={ver}', f'network/tmpfs={net}', f'migrations source={SRC}:{MIG}',
            f'migrations count={len(vers)} min={vers[0][0]} max={vers[-1][0]}', 'base ALLOW_CONNECTIONS false(템플릿 전용)']
    open(os.path.join(a.out, 'setup.txt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(log) + '\n')
    print('\n'.join(log))


if __name__ == '__main__':
    sys.exit(main())
