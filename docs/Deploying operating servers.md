# Ubuntu 운영서버 배포 가이드

## 개요

Docker Compose 기반 배포. 코드 변경 시 `git pull` + `docker compose up --build -d` 한 번으로 반영된다.

```
[Ubuntu 서버]
  ├── TimescaleDB (포트 5432, 내부 전용)
  ├── Redis       (포트 6379, 내부 전용)
  ├── Backend     (포트 8080)
  └── Frontend    (포트 3000)
```

---

## 1. 서버 초기 설정 (최초 1회)

### 1.1 필수 패키지 설치

```bash
sudo apt update && sudo apt upgrade -y

# Docker
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
newgrp docker   # 또는 로그아웃 후 재접속

# Docker Compose Plugin 확인
docker compose version

# Git
sudo apt install -y git
```

### 1.2 프로젝트 클론

```bash
# 홈 디렉터리에 클론 (경로는 원하는 곳으로 변경 가능)
cd ~
git clone <your-repo-url> crypto-auto-trader
cd crypto-auto-trader
```

> 비공개 저장소라면 SSH 키 또는 Personal Access Token을 미리 설정한다.

---

## 2. 환경 변수 설정 (최초 1회)

```bash
cd ~/crypto-auto-trader

# .env.example 복사
cp .env.example .env

# 편집
nano .env
```

`.env` 작성 예시:

```env
# Database
DB_PASSWORD=강력한_패스워드

# Upbit API (AES-256 암호화된 값)
UPBIT_ACCESS_KEY_ENCRYPTED=암호화된_액세스키
UPBIT_SECRET_KEY_ENCRYPTED=암호화된_시크릿키

# AES-256 복호화 키 (32자 이상)
AES_KEY=your-256-bit-secret-key-minimum-32-chars

# Telegram 알림 (선택)
TELEGRAM_BOT_TOKEN=123456:ABC-DEF...
TELEGRAM_CHAT_ID=your_chat_id

# 서버 IP 또는 도메인 (프론트엔드 API 주소에 사용)
HOST_IP=123.456.789.0
```

> `.env` 파일은 절대 git에 커밋하지 않는다. `.gitignore`에 이미 포함되어 있다.

---

## 3. 최초 배포

```bash
cd ~/crypto-auto-trader

# 운영용 docker-compose.prod.yml로 빌드 및 실행
docker compose -f docker-compose.prod.yml up --build -d
```

처음 실행 시 Docker 이미지 빌드(백엔드 Gradle, 프론트엔드 npm)로 **10~20분** 소요된다.

### 실행 확인

```bash
# 컨테이너 상태 확인
docker compose -f docker-compose.prod.yml ps

# 백엔드 로그 확인
docker compose -f docker-compose.prod.yml logs -f backend

# 프론트엔드 로그 확인
docker compose -f docker-compose.prod.yml logs -f frontend
```

정상 기동 후 접속:
- 대시보드: `http://서버IP:3000`
- API: `http://서버IP:8080/api/v1`
- Swagger: `http://서버IP:8080/swagger-ui.html`

---

## 4. 코드 수정 후 운영 서버 반영 (업데이트)

코드를 수정하고 git에 push한 뒤, 서버에서 아래 명령만 실행한다.

```bash
cd ~/crypto-auto-trader

# 1. 최신 코드 받기
git pull

# 2. 변경된 서비스만 재빌드 후 무중단 재기동
docker compose -f docker-compose.prod.yml up --build -d
```

> `--build` 플래그가 변경된 이미지만 다시 빌드하고, `-d`가 백그라운드로 실행한다.
> DB와 Redis는 코드 변경이 없으므로 재시작되지 않는다.

### 특정 서비스만 업데이트하고 싶을 때

```bash
# 백엔드만
docker compose -f docker-compose.prod.yml up --build -d backend

# 프론트엔드만
docker compose -f docker-compose.prod.yml up --build -d frontend
```

---

## 5. 유용한 운영 명령어

### 로그 확인

```bash
# 전체 로그 (실시간)
docker compose -f docker-compose.prod.yml logs -f

# 백엔드만 최근 200줄
docker compose -f docker-compose.prod.yml logs --tail=200 backend

# 에러만 필터
docker compose -f docker-compose.prod.yml logs backend | grep ERROR
```

### 서비스 재시작 / 중단

```bash
# 특정 서비스 재시작
docker compose -f docker-compose.prod.yml restart backend

# 전체 중단 (DB 데이터는 보존)
docker compose -f docker-compose.prod.yml down

# 전체 중단 + DB 데이터까지 삭제 (주의!)
docker compose -f docker-compose.prod.yml down -v
```

### DB 직접 접속

```bash
docker compose -f docker-compose.prod.yml exec db psql -U trader -d crypto_auto_trader
```

---

## 6. Nginx 리버스 프록시 설정 (선택 — 도메인 + HTTPS 사용 시)

포트 3000/8080 대신 80/443으로 서비스하려면 Nginx를 앞단에 둔다.

```bash
sudo apt install -y nginx certbot python3-certbot-nginx
```

`/etc/nginx/sites-available/crypto-trader` 파일 생성:

```nginx
server {
    listen 80;
    server_name your-domain.com;

    # 프론트엔드
    location / {
        proxy_pass http://localhost:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # 백엔드 API
    location /api/ {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/crypto-trader /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx

# HTTPS 인증서 (도메인 연결 후)
sudo certbot --nginx -d your-domain.com
```

---

## 7. 방화벽 설정

```bash
sudo ufw allow ssh
sudo ufw allow 3000   # 프론트엔드 (Nginx 미사용 시)
sudo ufw allow 8080   # 백엔드 API (Nginx 미사용 시)
# Nginx 사용 시 위 두 줄 대신:
# sudo ufw allow 80
# sudo ufw allow 443
sudo ufw enable
```

> 5432(DB), 6379(Redis)는 외부에 열지 않는다. Docker 내부 네트워크로만 통신한다.

---

## 8. 서버 재부팅 후 자동 기동

`docker-compose.prod.yml`의 모든 서비스에 `restart: always`가 설정되어 있으므로, Docker 데몬이 시작되면 자동으로 컨테이너가 재기동된다.

Docker 데몬 자동 시작 확인:

```bash
sudo systemctl enable docker
sudo systemctl status docker
```

---

## 빠른 참조

| 작업 | 명령 |
|------|------|
| 최초 배포 | `docker compose -f docker-compose.prod.yml up --build -d` |
| 코드 반영 | `git pull && docker compose -f docker-compose.prod.yml up --build -d` |
| 상태 확인 | `docker compose -f docker-compose.prod.yml ps` |
| 로그 보기 | `docker compose -f docker-compose.prod.yml logs -f backend` |
| 전체 중단 | `docker compose -f docker-compose.prod.yml down` |
| DB 접속 | `docker compose -f docker-compose.prod.yml exec db psql -U trader -d crypto_auto_trader` |
