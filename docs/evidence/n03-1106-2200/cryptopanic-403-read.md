# CryptoPanic HTTP 403 — 읽기 대조만 (2026-11-06 17:10 KST 무렵)

- 코드(1899b49 CryptoPanicSource): URL = news_source_config.url(V35 시드 `https://cryptopanic.com/api/v1/posts/`) + `?public=true`, api_key 가 있으면 `&auth_token=`. 클래스 주석은 "무료 플랜: auth_token 없이도 기본 사용 가능".
- 운영 DB 의 실제 url·api_key 유무는 읽지 않았다(운영 DB 비밀번호 없음 — 묻지 않음).
- 공식 문서 페이지(cryptopanic.com/developers/api/)는 본문을 가져오지 못했다(제목만).
- 공개 SDK 소스(검색 결과): 현재 클라이언트는 `https://cryptopanic.com/api/{plan}/v2/posts/` 경로와 인증 토큰을 쓴다. Python 클라이언트는 403(forbidden)을 인증 오류와 따로 둔다.
  출처: https://pkg.go.dev/github.com/tigusigalpa/cryptopanic-go , https://github.com/tigusigalpa/cryptopanic-php , https://pypi.org/project/cryptopanic/
- 판단: v1 경로·토큰 없는 공개 호출이 더는 허용되지 않아 403 이 난다는 것은 **가설**이다. 공식 문서로 확인하지 못했다. 원인 확정·설정 변경·실 API 시험·분류 변경은 하지 않았다(주말 지시 §7). 확인 방법(총괄 판단): 운영 news_source_config 의 url·키 유무 읽기, 공식 문서·플랜 확인.
