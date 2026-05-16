# BitMan JustBuy — Oracle Cloud 배포 가이드

Oracle Cloud Always Free (Ampere A1, Ubuntu 22.04, 4 vCPU / 24GB RAM / 200GB) 기준.

## 1. VM 기본 세팅

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y openjdk-21-jre-headless ufw fail2ban cloudflared
```

## 2. 전용 유저 및 디렉토리

```bash
sudo useradd -r -m -d /opt/justbuy -s /usr/sbin/nologin justbuy

sudo mkdir -p /opt/justbuy /var/lib/justbuy/data /var/log/justbuy /etc/justbuy
sudo chown -R justbuy:justbuy /opt/justbuy /var/lib/justbuy /var/log/justbuy
sudo chown root:justbuy /etc/justbuy
sudo chmod 750 /etc/justbuy
```

## 3. 환경변수 파일

```bash
sudo cp deploy/env.example /etc/justbuy/env
sudo chown root:justbuy /etc/justbuy/env
sudo chmod 640 /etc/justbuy/env
sudo vim /etc/justbuy/env   # 실키 입력

# JWT_SECRET 생성
openssl rand -base64 64
```

## 4. JAR 업로드

```bash
# 로컬에서 빌드
cd backend && ./gradlew bootJar

# 업로드 (scp 또는 rsync)
scp build/libs/justbuy-api-1.0.0.jar ubuntu@<VM_IP>:/tmp/
ssh ubuntu@<VM_IP> "sudo mv /tmp/justbuy-api-1.0.0.jar /opt/justbuy/justbuy-api.jar && sudo chown justbuy:justbuy /opt/justbuy/justbuy-api.jar"
```

## 5. systemd 서비스 등록

```bash
sudo cp deploy/justbuy.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable justbuy
sudo systemctl start justbuy

# 상태 확인
sudo systemctl status justbuy
sudo journalctl -u justbuy -f
tail -f /var/log/justbuy/justbuy.log
```

## 6. 방화벽

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp
# 8080 은 외부 직접 노출 금지 (Cloudflare Tunnel 만 사용).
sudo ufw enable
```

## 7. Cloudflare Tunnel

```bash
# tunnel 생성 (최초 1회)
cloudflared tunnel login
cloudflared tunnel create justbuy

# 라우팅 설정
# ~/.cloudflared/config.yml:
#   tunnel: <TUNNEL_ID>
#   credentials-file: /home/ubuntu/.cloudflared/<TUNNEL_ID>.json
#   ingress:
#     - hostname: api.bit-man.net
#       service: http://localhost:8080
#     - service: http_status:404

cloudflared tunnel route dns justbuy api.bit-man.net
sudo cloudflared service install
sudo systemctl start cloudflared
```

## 8. 배포 후 검증

```bash
# 헬스체크
curl https://api.bit-man.net/api/health

# 관리자 로그인 (최초 비밀번호는 journalctl 에서 확인)
sudo journalctl -u justbuy | grep "One-time admin password"

# 로그 롤링 동작 확인 (24h 후)
ls -lh /var/log/justbuy/archive/
```

## 9. 업데이트

```bash
# 로컬에서 새 JAR 빌드 및 업로드
scp build/libs/justbuy-api-1.0.0.jar ubuntu@<VM_IP>:/tmp/

ssh ubuntu@<VM_IP>
sudo systemctl stop justbuy
sudo mv /tmp/justbuy-api-1.0.0.jar /opt/justbuy/justbuy-api.jar
sudo chown justbuy:justbuy /opt/justbuy/justbuy-api.jar
sudo systemctl start justbuy
```

## 10. 주요 경로 요약

| 경로 | 용도 |
|------|------|
| `/opt/justbuy/justbuy-api.jar` | 실행 JAR (justbuy:justbuy) |
| `/etc/justbuy/env` | 환경변수 (root:justbuy 640) |
| `/var/lib/justbuy/data/` | H2 파일 DB |
| `/var/log/justbuy/` | 애플리케이션 로그 |
| `/var/log/justbuy/archive/` | 롤링 압축 로그 (30일 / 3GB) |
| `/etc/systemd/system/justbuy.service` | systemd 유닛 |

## 11. 보안 체크리스트

- [ ] `JWT_SECRET` 최소 64바이트 랜덤
- [ ] `ADMIN_DEFAULT_PASSWORD` 최초 1회 로그인 후 웹 UI에서 변경 → env 에서 삭제
- [ ] `/etc/justbuy/env` 권한 640 (root:justbuy)
- [ ] UFW 8080 포트 외부 차단
- [ ] Cloudflare Tunnel 만 공개 엔드포인트
- [ ] H2 콘솔 비활성 (application-prod.yml 에서 강제)
- [ ] fail2ban SSH 브루트포스 차단 활성화
