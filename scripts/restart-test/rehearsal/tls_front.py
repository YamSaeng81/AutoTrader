"""시험용 TLS 중계 — 127.0.0.1:443 의 TLS 를 풀어 거래소 대역(127.0.0.1:18080, 평문 HTTP)으로 넘긴다.

V80 앱은 주소가 https://api.upbit.com 으로 고정돼 있다. 시험 JVM 만 api.upbit.com 을 127.0.0.1 로 풀고 이 인증서를 신뢰한다.
"""
import asyncio
import os
import ssl
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
UP = ('127.0.0.1', int(sys.argv[1]) if len(sys.argv) > 1 else 18080)


async def pipe(r, w):
    try:
        while True:
            b = await r.read(65536)
            if not b:
                break
            w.write(b)
            await w.drain()
    except Exception:
        pass
    finally:
        try:
            w.close()
        except Exception:
            pass


async def handle(cr, cw):
    try:
        ur, uw = await asyncio.open_connection(*UP)
    except Exception:
        cw.close()
        return
    await asyncio.gather(pipe(cr, uw), pipe(ur, cw))


async def main():
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(os.path.join(HERE, 'cert.pem'), os.path.join(HERE, 'key.pem'))
    srv = await asyncio.start_server(handle, '127.0.0.1', 443, ssl=ctx)
    print('tls front on 127.0.0.1:443 ->', UP, flush=True)
    async with srv:
        await srv.serve_forever()


asyncio.run(main())
