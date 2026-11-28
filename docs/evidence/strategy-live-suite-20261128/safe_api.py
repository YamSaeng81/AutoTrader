"""Localhost-only API adapter; authentication stays in server process memory."""
import json
import pathlib
import re
import sys
import urllib.request

def request(method, path, body, token, opener=urllib.request.urlopen):
    allowed_get = path in ("/api/v1/account/summary", "/api/v1/trading/health/exchange", "/api/v1/settings/upbit/order-chance?market=KRW-BTC")
    allowed_post = path == "/api/v1/trading/sessions" or re.fullmatch(r"/api/v1/trading/sessions/[1-9][0-9]*/(start|stop)", path)
    if method not in ("GET", "POST") or (method == "GET" and not allowed_get) or (method == "POST" and not allowed_post):
        raise ValueError("PATH_OR_METHOD_REJECTED")
    if not token or "\n" in token or "\r" in token:
        raise ValueError("AUTH_NOT_CONFIGURED")
    req = urllib.request.Request("http://127.0.0.1:8080" + path, data=body.encode("utf-8") if body else None,
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"}, method=method)
    with opener(req, timeout=25) as response:
        if response.status < 200 or response.status >= 300:
            raise ValueError("HTTP_NON_SUCCESS")
        data = json.loads(response.read())
    if not isinstance(data, dict) or data.get("success") is not True or not isinstance(data.get("data"), dict) or "error" in data["data"]:
        raise ValueError("APPLICATION_NON_SUCCESS")
    return data

def main():
    try:
        method, path, body = sys.argv[1:]
        values = [line.split("=", 1)[1].strip() for line in pathlib.Path(".env").read_text().splitlines() if line.startswith("API_AUTH_TOKEN=")]
        if len(values) != 1:
            raise ValueError("AUTH_NOT_CONFIGURED")
        print(json.dumps(request(method, path, body, values[0]), ensure_ascii=True))
    except Exception as exc:
        print(json.dumps({"success": False, "error": "API_REQUEST_FAILED", "category": type(exc).__name__}))
        raise SystemExit(1)

if __name__ == "__main__":
    main()
