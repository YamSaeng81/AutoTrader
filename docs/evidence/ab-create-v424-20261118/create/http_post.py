"""생성 POST 전송부 v4.2 (총괄 v1.178 R1, 2026-11-17) — 8팔 생성기(ab_create v4.2)와 SMA20 단건 도구가 같이 쓴다.

R1: 기본 urllib.request.urlopen 은 301·302·303 의 Location 을 자동으로 따라가며 POST 를 GET 으로 바꾼다. Location 이 전체 목록이면
사전 등록 522~587 의 성과 필드가 든 응답을 읽게 된다. 이 전송부는
  - 리다이렉트 처리기를 **어떤 3xx 도 따라가지 않는 것**으로 바꾼다(같은/다른 경로·호스트, 301/302/303/307/308 모두 두 번째 요청 0).
  - 환경 변수 프록시를 쓰지 않는다(ProxyHandler({})) — 기본 URL 은 검토한 backend 직접 경로여야 한다.
  - 3xx 응답 본문은 읽지 않는다. 결과는 (코드, None, "리다이렉트") — 분류는 응답 불명(접수 여부 모름)이며 호출기는 남은 전송을 멈춘다.
  - 응답의 최종 URL 이 요청 URL 과 다르면(이 전송부에서는 일어나지 않아야 한다) 같은 응답 불명으로 둔다.
  - POST 한 번만 보낸다. 재시도·재전송 없음.
토큰은 헤더에만 싣고 어떤 반환값에도 넣지 않는다.
"""
from __future__ import annotations

import json
import urllib.error
import urllib.request

REDIRECT = "리다이렉트"
URL_CHANGED = "최종 URL 다름"


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """어떤 3xx 도 따라가지 않는다 — None 을 돌려주면 urllib 는 HTTPError(3xx)를 낸다."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):   # noqa: D401
        return None


def build_opener() -> urllib.request.OpenerDirector:
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect())


def post_once(opener: urllib.request.OpenerDirector, url: str, token: str, body: dict | None, timeout: float
              ) -> tuple[int | None, dict | None, str | None]:
    """(HTTP 코드, JSON, 전송 오류 분류). 3xx 는 본문을 읽지 않고 (코드, None, REDIRECT)."""
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST",
                                 headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    try:
        with opener.open(req, timeout=timeout) as r:
            code = r.status
            if 300 <= code < 400:
                return code, None, REDIRECT
            if r.geturl() != url:
                return code, None, URL_CHANGED
            raw = r.read()
    except urllib.error.HTTPError as e:
        if 300 <= e.code < 400:
            e.close()
            return e.code, None, REDIRECT
        code, raw = e.code, e.read()
    except Exception as e:   # 시간 초과·연결 오류 — 접수됐을 수 있다
        return None, None, type(e).__name__
    try:
        return code, json.loads(raw.decode("utf-8")), None
    except Exception:
        return code, None, "JSON 아님"
