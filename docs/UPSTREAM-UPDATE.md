# PocketRisu 버전이 바뀔 때 — 앱 점검·갱신 절차

PocketRisu(서버)는 약 2주마다 릴리스된다. 앱은 서버의 **API 경로·응답 형식·주입 전역변수**에만 의존하므로, 새 버전이 나오면 아래를 순서대로 확인하고 결과를 이 문서 맨 아래 표에 누적한다.

## 0. 먼저 알아둘 것

| 사실 | 의미 |
|---|---|
| 서버가 프론트(`dist/`)를 같이 배포한다 | 1단계(서버 UI 모드)에서는 **UI 변경은 앱과 무관**. 앱이 깨지는 건 API 계약이 바뀔 때뿐 |
| NAS 업데이트는 사용자가 `docker compose pull` 로 직접 한다 | 앱 릴리스를 먼저 확인하고 NAS를 올리는 순서를 지키면 공백이 없다 |
| 앱은 매 실행 시 `/api/update-check`로 서버 버전을 읽는다 | 검증된 버전 목록(`VERIFIED_SERVER_VERSIONS`) 밖이면 진단 화면에 "검증되지 않은 서버 버전" 표시 |
| 2단계(번들 모드)는 서버 버전 == 번들 버전일 때만 켜진다 | 버전이 다르면 서버 UI로 자동 폴백. 데이터 포맷 불일치 경로는 없음 |

## 1. 릴리스가 나오면 (앱 코드 수정 전)

1. 릴리스 노트 읽기: https://github.com/PocketRisu/PocketRisu/releases — 특히 "⚠️ 업데이트 주의", `RISU_MIN_VERSION`, 저장 포맷·동기화·에셋·인증 관련 항목.
2. 계약 파일 diff 확인 (아래 명령). 변경이 **없으면** 3·4단계는 생략하고 검증 목록만 갱신.

```bash
# 예: v1.14.0 → v1.15.0
gh api "repos/PocketRisu/PocketRisu/compare/v1.14.0...v1.15.0" \
  --jq '.files[] | select(.filename | test("server/node/server[.]cjs|server/node/model-jobs[.]cjs|src/ts/storage/(nodeStorage|risuSave|database[.]svelte|chatDeltaSync|chatStorage)[.]ts|src/ts/platform[.]ts|src/ts/parser/parser[.]svelte[.]ts|src/ts/process/stableDiff[.]ts")) | "\(.filename) +\(.additions) -\(.deletions)"'
```

3. 아래 **계약 체크리스트**를 항목별로 소스에서 대조한다.

## 2. 계약 체크리스트 (앱이 의존하는 것 전부)

| # | 앱 기능 | 의존하는 서버/클라이언트 동작 | 확인 위치 (v1.14.0 기준) | 바뀌면 |
|---|---|---|---|---|
| C1 | 서버 버전 표시·버전 게이트 | `GET /api/update-check` → `currentVersion`, `deploymentType` (인증 불필요) | `server.cjs` `/api/update-check` | `ServerProbe` 수정 |
| C2 | 설정 화면 연결 확인 | `GET /api/test_auth` → `{status: unset\|incorrect\|success}` | `server.cjs` `/api/test_auth` | `ServerProbe` 수정 |
| C3 | 네이티브 요청의 세션 공유 | 세션 쿠키 이름 `risu-session`, `HttpOnly; SameSite=Strict`, 7일 | `server.cjs` `/api/session` | `Http` 쿠키 브리지·마스킹 규칙(`Redactor`) 수정 |
| C4 | 삽화 캐시 | `GET /api/asset/:hex` — `Cache-Control: immutable`, `ETag`, 키 = hex(`inlay/<id>` 등), `inlay_thumb/` 썸네일 | `server.cjs` `/api/asset` | `AssetCache` 키 규칙·무효화 정책 재검토 |
| C5 | db.bin 조건부 캐시 | `GET /api/read` + 헤더 `file-path`(hex), 응답 헤더 `x-db-etag`, `If-None-Match` 304 | `server.cjs` `/api/read`, `nodeStorage.ts` `_lastDbEtag` | `DbBlobCache` 수정 |
| C6 | 채팅 본문 캐시·폴백 | `GET /api/chat-content/:chaId/:idx` + 헤더 `x-chat-id`, 델타 헤더 `x-chat-base-count/fp`, 응답 `Vary` | `server.cjs` `sendChatContent` | 델타 헤더 있으면 **가로채지 않는** 규칙 유지 여부 확인 |
| C6a | 패치 010 (첫 바이트 타임아웃 120초) | `const FIRST_BYTE_TIMEOUT_MS = 30_000` 한 줄 | `src/ts/storage/nodeStorage.ts` 상단 | 상수가 옮겨지면 패치 재생성; upstream이 직접 늘리면 패치 삭제 |
| C7 | 읽기 전용 디코더 | `RISUSAVE` 매직 11B, 버전 7(msgpack)·8(zlib)·9(gzip), msgpackr `useRecords:false`, `int64AsType:'number'` | `risuSave.ts` 상단 | 디코더 버전 분기 추가. **새 버전 바이트가 나오면 디코더는 자동 비활성(폴백)** |
| C8 | 채팅 쓰기 후 캐시 무효화 | 쓰기 경로 = `POST /api/chat-content/:chaId/:idx`, `POST /api/patch`, `POST /api/write` | `server.cjs` 해당 라우트 | 무효화 트리거 경로 목록 갱신 |
| C9 | 삽화 생성 직후 프리페치 | 삽화 업로드 경로와 키 형식 (`/api/write` + `file-path`=hex(`inlay/...`)) | `stableDiff.ts`, `globalApi.svelte.ts` 업로드부 | 트리거 B 경로 수정 |
| C10 | 생성 감지(포그라운드 알림) | `POST /api/model-jobs`, `GET /api/model-jobs/:id/stream`, `DELETE /api/model-jobs/:id` | `model-jobs.cjs` `registerRoutes` | `GenWatcher` 경로 수정 |
| C11 | 메시지 내 이미지 참조 | `{{inlay::id}}`, `{{inlayed::id}}`, `{{inlayeddata::id}}` | `parser.svelte.ts`, `src/ts/process/` | 내보내기·프리페치 정규식 갱신 |
| C12 | [2단계] 번들 모드 | 서버가 `index.html`에 주입하는 전역: `__NODE__`, `__PATCH_SYNC__` 뿐. 클라이언트 API 호출은 전부 상대경로 | `server.cjs` `app.get('/')`, `platform.ts` | 주입 전역이 늘면 번들 `index.html`에 동일 반영 |
| C13 | [2단계] 패치 적용 | `patches/*.patch`가 새 태그에 깨끗이 적용되는가 | CI `upstream-sync.yml` | 충돌 시 리베이스 |
| C14 | 인증 흐름 | 비밀번호 → `POST /api/login` → 서버 JWT(5분) + `/api/token/refresh`; `/api/session` 쿠키 | `server.cjs` 3731~3800 | 자동 로그인(옵션) 로직 수정 |

## 2a. 자동화된 부분 (`upstream-sync.yml`, 매일 12:17 KST)

1. PocketRisu 최신 릴리스 태그가 `upstream.lock`보다 새로우면 그 태그로 `scripts/build-frontend.sh`(패치 적용 + 번들 빌드)를 시도한다.
2. 성공 → **PR `upstream: PocketRisu vX.Y.Z`** 가 열린다(`upstream.lock`만 변경, 본문에 계약 파일 diff 요약).
3. 실패 → 이슈 `upstream vX.Y.Z: 번들 빌드/패치 실패 — 리베이스 필요`.
4. PR은 사람이 §2 체크리스트를 보고 병합한다. 병합되면 nightly가 새 번들로 빌드된다.

번들 버전 ≠ 서버 버전이면 앱이 서버 UI로 자동 폴백하므로, PR을 늦게 병합해도 앱은 멈추지 않는다 — 번들 모드만 꺼진다.

## 3. 앱을 고쳐야 할 때

1. 해당 모듈 수정 + 단위 테스트(`:app:testDebugUnitTest`).
2. 실제 서버(새 버전)로 **진단 화면 로그 확인**: `I/Setup server reachable version=<새 버전>`, 캐시 히트/미스 줄이 정상인지, `E/` 줄이 없는지. 로그를 내보내 `docs/MEASUREMENTS.md`에 요약 첨부.
3. `app/build.gradle.kts`의 `VERIFIED_SERVER_VERSIONS`에 새 버전 추가.
4. `docs/TROUBLESHOOTING.md`에 문제가 있었다면 항목 추가, `docs/WORKLOG.md`에 검증 명령·결과.
5. 태그 `vX.Y.Z` push → `release.yml`이 서명 APK 릴리스 → 앱 내 업데이트로 설치.
6. 그 다음 NAS `docker compose pull && docker compose up -d`.

## 4. 사용자 운영 순서 (요약)

```
PocketRisu 릴리스 공지
 → (앱 저장소) 호환 확인/릴리스   ← 이 문서 §1~§3
 → 태블릿에서 앱 업데이트
 → NAS docker compose pull
 → 앱 실행 → 진단 화면에서 서버 버전·E 로그 확인
```

순서가 바뀌어(NAS 먼저) 서버 UI로 떠도 앱은 동작한다. 캐시 기능이 자동 비활성됐다면 진단 화면에 `W/Intercept … disabled (decode failed)` 로 남는다.

## 5. 검증 이력

| 서버 버전 | 앱 버전 | 계약 변경 | 조치 | 확인일 |
|---|---|---|---|---|
| 1.14.0 | 0.1.0 (M1) | — (기준) | — | 2026-10-16 |
