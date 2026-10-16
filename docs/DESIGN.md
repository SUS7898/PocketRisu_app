# PocketRisu Android 앱 설계서

작성일: 2026-10-16 · 기준 서버: PocketRisu v1.14.0 (2026-10-09) · 상태: **제안 (착수 전)**

---

## 0. 한 줄 요약

PocketRisu 서버의 웹 UI를 그대로 쓰는 WebView 앱에, **네이티브 요청 가로채기 층**(디스크 캐시·프리페치·내보내기·보안)을 얹는다. 2단계에서 같은 가로채기 층으로 **APK에 번들한 포크 프론트**를 서버 origin에 공급하되, 서버 버전이 번들과 다르면 자동으로 서버 UI로 되돌아간다. 성능 이득의 대부분은 1단계 캐시 층에서 나오고, 2단계는 측정 후 남는 병목(S7+ 렌더링)에만 쓴다.

---

## 1. 문제 정의와 범위

### 1.1 사용 환경
- 서버: Synology NAS, Docker `ghcr.io/pocketrisu/pocketrisu`, 역방향 프록시 HTTPS.
- 단말: Galaxy Tab S7+ (Snapdragon 865, Android 13 최종) / Galaxy S26 Ultra. 태블릿에서 분할화면 상용.
- 데이터: 홈 진입 시 `database.bin` 100MB+, 모듈 다수·대용량, 채팅·캐릭터 다수, 턴당 NAI 삽화 12장.
- 모델: 메인 Gemini 3.1 Pro, 보조 Gemini 3.8 Flash. 턴당 S7+ 90~180초, S26 50~80초.

### 1.2 앱이 줄일 수 있는 것 / 없는 것

| 구간 | 앱이 할 수 있는 일 | 근거 |
|---|---|---|
| 홈 진입 100MB 다운로드 | ETag 조건부 캐시로 **변경 없으면 0바이트** | 서버가 `x-db-etag` 반환 (`server.cjs /api/read`) |
| 채팅 열기 대기 / 안 열려서 왔다갔다 | 첫 바이트 타임아웃 단축 + 재시도 + 최근 채팅 프리페치 | 클라이언트 `FIRST_BYTE_TIMEOUT_MS = 30s` |
| 삽화 12장 재다운로드 | 영구(LRU) 디스크 캐시, 썸네일 선표시 | `/api/asset/*`가 `immutable, max-age=1y` + `inlay_thumb/` 엔드포인트 존재 |
| S7+ 렌더링 느림 | 1단계: 하드웨어 가속·렌더러 우선순위. 2단계: 포크 패치(지연 렌더링) | WebView = Chrome과 같은 엔진이므로 캐시 외 이득은 2단계에서 |
| Gemini/NAI 생성 시간 | **불가** | 서버·외부 API 시간 |

### 1.3 비목표
- 채팅 UI 네이티브 재구현 (CBS·정규식·플러그인·Lua 재현 불가).
- 서버 코드 수정 (NAS의 컨테이너는 upstream 이미지를 그대로 씀).
- Google Play 배포.

---

## 2. 아키텍처

```
┌─ APK ────────────────────────────────────────────────────────────────┐
│ MainActivity                                                          │
│  └─ WebView  ──(모든 요청)──►  InterceptLayer (WebViewClient)          │
│                                  │                                     │
│                                  ├─ GET /api/asset/*        → AssetCache (LRU 디스크, ETag)   │
│                                  ├─ GET /api/read (db.bin)  → DbBlobCache (ETag 조건부)       │
│                                  ├─ GET /api/chat-content/* → ChatCache (full 응답만)        │
│                                  ├─ [2단계] GET /, /assets/* → BundledUi (버전 일치 시)       │
│                                  └─ 그 외                    → null (WebView가 직접 NAS로)   │
│                                                                        │
│ Native 서비스: Prefetcher · Exporter · Updater · AppLock · GenWatcher   │
│ JS 브리지: window.PocketRisuApp (origin 검증, 최소 표면)                 │
└──────────────────────────────┬────────────────────────────────────────┘
                               │ HTTPS (시스템 CA + TOFU 핀 옵션)
                      Synology 역프록시 → PocketRisu 컨테이너
```

### 2.1 왜 로컬 프록시가 아니라 `shouldInterceptRequest`인가
앞선 논의에서는 `127.0.0.1` 프록시를 그렸지만, 가로채기 방식이 더 낫다.
- WebView의 origin이 **실제 NAS 주소 그대로**라 `risu-session` 쿠키(`HttpOnly; SameSite=Strict`)가 `<img src="/api/asset/…">`에 자연스럽게 붙는다. 프록시면 쿠키 도메인 재작성이 필요했다.
- 열린 localhost 포트가 없어 다른 앱이 프록시를 두드릴 공격면이 사라진다.
- 가로채지 않는 요청은 `null`을 돌려주면 WebView가 평소처럼 처리하므로 **SSE 스트림(`/api/model-jobs/:id/stream`), POST, 업로드**는 손대지 않는다.
- 2단계 번들 모드도 같은 훅으로 `GET /`와 `/assets/*`에 APK 자산을 돌려주면 끝난다.

제약: 가로채기는 GET 중심(POST 본문은 볼 수 없음), 응답 헤더로 쿠키를 심을 수 없음. 설계상 둘 다 필요 없다.

### 2.2 실패 시 동작 원칙 (업데이트 내성)
- 가로채기 층은 **경로 3개**(`/api/asset`, `/api/read`, `/api/chat-content`)만 안다. 서버가 응답 형식을 바꿔 디코딩이 실패하면 해당 경로의 캐시 기능만 끄고 원래 네트워크로 흘린다(앱은 계속 동작).
- 서버 버전은 `/api/update-check`의 `currentVersion`으로 매 실행 시 읽어 진단 화면에 표시하고, 앱이 검증한 버전 범위를 벗어나면 "검증되지 않은 서버 버전" 배너를 띄운다.

---

## 3. 모듈 설계

### 3.1 Shell (`app/shell`)
- WebView 설정: JS on, DOM storage on, `fileAccess/contentAccess` off, `mixedContent NEVER`, Safe Browsing on, 하드웨어 가속, `setRendererPriorityPolicy(IMPORTANT, false)`.
- `onRenderProcessGone` → WebView 재생성 + 마지막 URL 복구 (저메모리 S7+ 대비).
- 파일 선택(`onShowFileChooser`)·다운로드(`DownloadListener` → `DownloadManager`) 연결: 캐릭터 카드/백업 가져오기·내보내기가 웹 UI에서 그대로 동작.
- 뒤로가기: SPA 히스토리 우선, 루트에서 두 번 눌러 종료.
- `android:resizeableActivity="true"`, 멀티윈도우 크기 변경 시 WebView 재레이아웃만(재로드 금지).
- 앱 메뉴(네이티브 바텀시트, 화면 가장자리 스와이프 또는 상단바 버튼): 내보내기 · 캐시/진단 · 잠금 · 업데이트 · 설정.

### 3.2 InterceptLayer (`app/intercept`)
인증 사실(검토 R1): `/api/asset`만 세션 **쿠키**(`sessionAuthMiddleware`)로 인증하고, `/api/read`·`/api/chat-content`는 클라이언트가 매 요청에 붙이는 **`risu-auth` JWT(5분 만료) + `x-session-id` 헤더**로 인증한다. 따라서 가로채기 층은 WebView가 보낸 요청 헤더(`WebResourceRequest.requestHeaders`: `file-path`, `risu-auth`, `x-chat-id`, `x-chat-base-*`, `If-None-Match` 포함, Cookie 제외)를 **그대로 전달**해 네이티브로 요청한다. JWT는 메모리에만 잠시 들고(로그·디스크 금지, `Redactor`가 마스킹), 보관·갱신은 하지 않는다(백그라운드 프리페치 C를 2단계로 미룬 이유).

| 경로 | 정책 | 무효화 |
|---|---|---|
| `GET /api/asset/:hex` | **cache-first + 백그라운드 재검증**(stale-while-revalidate). 히트면 즉시 응답하고 뒤에서 `If-None-Match`로 확인, 200이면 교체. 서버는 `immutable`이라 하지만 같은 id로 내용이 바뀌는 경로가 실제로 있음(R4: `/api/inlays/compress` webp 재기록, `writeInlayImage` 동일 id 덮어쓰기, `assets/*`의 `updated_at` ETag). Range 요청은 캐시 파일에서 206으로 응답(인레이 mp4/webm 재생·탐색 보장) | 404 수신 시 키 삭제. `POST /api/inlays/compress` 통과 관찰 시 `inlay/`·`inlay_thumb/` 전체 무효화. 상한 초과 시 LRU |
| `GET /api/read` (`file-path` = `database/database.bin`) | **tee + 계측 (1단계)**. 헤더 전달 → 응답을 WebView로 흘리며 첫바이트·총 바이트·소요시간을 기록. **DbBlobCache는 측정이 정당화할 때만 추가**(R3: 서버는 304 전에도 100MB msgpack 인코딩을 매번 수행하고, 전송은 gzip이라 실제 바이트는 훨씬 작으며, 클라이언트의 100MB 디코드·Svelte 상태 구축 시간은 앱 캐시로 못 줄임). 추가 시 규칙: 캐시 blob을 200으로 돌려줄 때 반드시 저장해 둔 값을 `x-db-etag`로 실어야 함(서버 304엔 이 헤더가 없고, 빠지면 클라이언트 `_lastDbEtag`가 비어 `/api/write`의 `x-if-match` 충돌 감지가 꺼짐) | ETag 불일치 시 교체 |
| `GET /api/chat-content/:chaId/:idx` | **tee + 계측만. 캐시·폴백 없음** (R2). 헤더(델타 헤더 포함)를 전달해 네이티브로 요청하고 본문을 WebView와 Prefetcher에 동시에 흘린다. 폴백을 두면 안 되는 이유: 클라이언트는 40메시지 이상 채팅 24개를 IndexedDB에 복사해 두고 `x-chat-base-count/fp`로 델타만 받으므로 "큰 채팅"은 어차피 캐시 대상 밖이고, 옛 전체본을 돌려주면 클라이언트가 그것을 최신으로 믿고 저장 시 409→전체 저장으로 **서버의 더 새로운 메시지를 덮어쓴다**. 또 `/api/patch`는 URL에 chaId가 없어 무효화 트리거도 성립하지 않는다 | — |

- "채팅이 안 열림" 문제는 캐시가 아니라 **원인 측정**으로 접근: tee 계측으로 첫바이트 대기 / 본문 전송 / (WebView) 디코드 중 어디서 막히는지 로그에 남기고(`I/Intercept chat-content chaId=… firstByte=…ms total=…ms bytes=…`), 결과에 따라 2단계 패치(클라이언트 타임아웃·재시도) 또는 서버 측 조치를 정한다.
- 캐시 저장소: `DiskLruCache`(Jake Wharton) — assets(기본 상한 10GB, 설정 1~50GB). 항목마다 ETag·Content-Type·길이 메타 보관.
- 진단: 요청별 소요시간·히트/미스를 링버퍼에 기록 → 진단 화면에서 "홈 로드 시간, 채팅 열기 시간, 삽화 히트율(WebView HTTP 캐시 대비)" 표시. **이 수치가 1·2단계 효과 판정 기준.**
- 폴백 원칙: 네이티브 요청이 실패하거나 응답 형식이 예상과 다르면 해당 경로의 가로채기를 그 세션 동안 끄고(`W/Intercept … disabled`) `null`을 돌려 WebView가 직접 요청하게 한다.

### 3.3 RisuSave 디코더 (`app/risusave`, 읽기 전용)
- 형식: 11바이트 매직 `00 "RISUSAVE" 00 <ver>` + 페이로드. ver 7 = msgpack 원본, 8 = zlib(fflate) 압축, 9 = gzip 스트림. 이와 별도로 `"RISUSAVE\0"`로 시작하는 블록 포맷(ver 바이트 없음)도 존재(R6). **서버가 `/api/read`·`/api/chat-content`에 실제로 내보내는 건 ver 7 비압축뿐**(`encodeRisuSaveLegacy` 기본값).
- 1단계 구현: **ver 7만** 지원. 8/9/블록 포맷은 "디코딩 불가 → 기능 비활성 + `W/RisuSave unsupported format`". `jackson-dataformat-msgpack` 스트리밍 파서로 **필요한 키만** 추출 (characters[].name/chaId/chats[].id/name/lastDate, chat.message[]). msgpackr 옵션(`useRecords:false`, `int64AsType:'number'`)은 표준 msgpack 호환이나, 인코더가 안전 범위 밖 정수를 int64로 쓸 수 있으니 long/BigInteger 처리, 모르는 ext 타입은 건너뛴다.
- 100MB를 1패스 스캔하는 시간(S7+)을 `MEASUREMENTS.md` 항목에 넣는다. 느리면 db blob 디코딩은 포기하고 chat-content(수 MB) 디코딩만 쓴다.
- **절대 쓰지 않는다.** 인코딩·`/api/patch` 전송은 전부 웹 클라이언트 몫. 디코딩 실패 = 해당 기능 비활성 + 진단 로그.
- 사용처: Prefetcher(삽화 ID 추출), Exporter(메시지·이름), 최근 채팅 목록.

### 3.4 Prefetcher (`app/prefetch`)
- 트리거 A — 채팅 열림: tee한 `chat-content` 본문(전체본이든 델타든)을 디코딩해 `{{inlay::id}}`/`{{inlayed::id}}`/`{{inlayeddata::id}}` ID를 뽑아 `/api/asset/hex("inlay_thumb/"+id)`와 `hex("inlay/"+id)`를 **동시 4개**로 선다운로드(에셋 엔드포인트는 쿠키 인증이라 JWT 불필요). 델타 응답에는 새 메시지만 있지만, 옛 메시지의 삽화는 이전에 표시돼 이미 캐시에 있을 가능성이 높다.
- 트리거 B — 삽화 생성 완료: NAI 결과는 웹 클라이언트가 `POST /api/write` + `file-path`=hex(`inlay/<id>`)로 올린다(`writeInlayImage → NodeStorage.setItem`, R5에서 확인). 가로채기 층이 그 URL·헤더를 관찰하면 직후 해당 inlay(+썸네일)를 캐시에 당겨 온다 → 생성 끝나고 화면에 뜰 때 네트워크 0.
- 트리거 C — 앱 시작/유휴에 최근 채팅 본문 갱신: **2단계로 이동**. `chat-content`는 JWT 인증이라 백그라운드에서 쓰려면 토큰 보관·`/api/token/refresh` 갱신 로직이 필요하고, 이는 "토큰을 앱이 들지 않는다"는 1단계 보안 원칙과 상충한다.
- 서버 부하 상한: 동시 4, 초당 요청 캡, 생성 중(GenWatcher 활성)에는 프리페치 일시정지.

### 3.5 Exporter (`app/export`) — 단일 HTML
- 입력: 현재 채팅 = 가로채기 층이 마지막으로 본 `chat-content`의 `(chaId, x-chat-id)`. 앱 메뉴에서 다른 채팅 선택도 가능(디코딩한 db blob의 캐릭터/채팅 목록).
- 데이터: `chat-content`(full) 디코딩 → 메시지 배열(role, data, time, name). 이미지는 캐시 우선, 없으면 다운로드.
- 렌더링: 메시지 본문의 마크다운·`{{inlay}}` 태그만 변환하는 **원문 기반**(CBS/정규식 스크립트는 적용하지 않음 — 1단계 가정). 2단계에서 "화면 그대로"(DOM 스냅샷) 옵션 검토.
- 이미지 옵션: 원본 / 긴 변 2048 / 1024 / 512 · WebP 품질 60~95 · 썸네일만. 미리보기: 선택 설정으로 앞 6장을 실제 변환해 보여 줌. 예상 용량: 표본 6장의 압축비 × 전체 원본 바이트 + 텍스트 길이로 추정, 변환 중 실측치로 갱신.
- 출력 HTML: 이미지는 `data:` WebP, 메시지 접기/펼치기, 검색, 라이트박스, 다크모드, 외부 스크립트 0 (오프라인·공유 안전). 저장은 SAF(`ACTION_CREATE_DOCUMENT`) → 다운로드 폴더/드라이브 등 사용자가 선택.
- 2단계 후보: ZIP(JSON + images/), PDF.

### 3.6 Updater (`app/update`)
- 채널: GitHub Releases(`SUS7898/PocketRisu_app`). 앱 시작 시(최대 하루 1회) 최신 태그·`versionCode` 비교.
- 흐름: 릴리스 노트 표시 → 사용자 확인 → APK를 앱 캐시 디렉터리로 다운로드 → **SHA-256 대조**(릴리스 자산 `.sha256`) → `PackageManager.getPackageArchiveInfo`로 **서명 인증서가 현재 앱과 동일한지 검증** → `PackageInstaller` 세션 커밋.
- 경고 최소화의 한계: 최초 설치 1회의 "알 수 없는 앱 설치 허용"은 Android 정책상 제거 불가. 같은 키로 서명된 업데이트는 데이터 유지·재설치 없음. 앱이 자기 자신을 설치한 이후부터는(installer of record) API 31+ `USER_ACTION_NOT_REQUIRED`로 확인창 생략 시도, 거부되면 표준 확인창.
- 롤백: 이전 APK 1개를 보관, 설정에서 재설치 가능.

### 3.7 AppLock (`app/lock`)
- 선택 기능(기본 off). `BiometricPrompt` + 기기 자격증명 폴백. 잠금 시점: 실행 시, 백그라운드 N분 후(기본 5분). 잠금 중에는 WebView를 가리는 오버레이 + 최근 앱 화면 스냅샷 차단(`FLAG_SECURE` 옵션).

### 3.8 GenWatcher (`app/gen`)
- LLM 텍스트: `POST /api/model-jobs` 통과를 보면 생성 시작, `/api/model-jobs/:id/stream` 종료·`DELETE`를 보면 끝. (서버사이드 job이라 연결이 끊겨도 서버는 계속 생성 — 앱은 "진행 중" 상태만 추적.)
- 삽화(NAI 등): **model-jobs를 거치지 않는다**(R5). `globalFetch → POST /proxy2` + `risu-url` 헤더로 브라우저가 직접 요청하므로, `/proxy2` 요청의 시작·완료(응답 종료)를 관찰해 "삽화 생성 중"으로 센다. 12장은 순차 요청이라 `/proxy2` 미완료 1건 = 생성 중.
- 포그라운드 서비스 유지 조건 = model-job 활성 **또는** `/proxy2` 미완료. 완료 시 알림(앱이 전면이 아닐 때만). 화면 꺼짐 방지는 옵션. 서비스는 앱 프로세스를 지킬 뿐 렌더러는 별개이므로 `setRendererPriorityPolicy(IMPORTANT)`를 유지한다(R9).
- 레거시 모델 바인딩(브라우저 직접 요청)도 `/proxy2`를 타므로 같은 방식으로 감지된다.

### 3.10 AppLog · 진단 (`app/log`, `app/ui/diagnostics`)
사용자가 원격(태블릿)에서 앱이 제대로 동작하는지 **로그로 직접 확인**할 수 있어야 한다. 상세 규칙은 `docs/LOGGING.md`.
- 수집: 메모리 링버퍼(최근 2,000줄) + `filesDir/logs/app-N.log` 회전 파일(512KB × 5). 레벨 D/I/W/E, 태그 = 모듈명(`Shell`, `Intercept`, `Cache`, `Prefetch`, `Export`, `Update`, `Lock`, `Gen`, `Net`).
- 마스킹(**기록 시점**에 적용, 내보낼 때가 아님): `risu-auth`·`Authorization`·`Cookie`·`Set-Cookie` 헤더 값, JWT 형태 문자열, `password=` 류 필드, 채팅 본문·캐릭터 이름(아예 로그에 넣지 않음). 서버 호스트명은 내보내기 시 `<server>`로 치환(설정에서 포함 선택 가능).
- 내보내기: 진단 화면 → "로그 내보내기" → SAF로 파일 저장 또는 공유 시트. 파일 머리에 앱 버전·기기·Android 버전·서버 버전(`/api/update-check`)·설정 요약(민감값 제외)을 붙인다.
- 진단 화면: 서버 연결 상태·버전, 실행 모드(1단계: 항상 서버 UI), 캐시 사용량/히트율(2·3 마일스톤부터), 최근 로그 tail, 로그 지우기.

### 3.9 Security (`app/security`, 그리고 CI)
- 전송: `usesCleartextTraffic=false`, 네트워크 보안 설정으로 HTTPS 강제. 인증서 고정은 **TOFU** 방식(첫 접속 시 서버 SPKI 저장, 바뀌면 차단+확인 창) — Let's Encrypt 90일 갱신으로 하드 핀이 깨지는 문제 회피.
- 저장: 세션 쿠키는 WebView 저장소(Android 파일 기반 암호화 적용). 앱은 JWT를 디스크에 쓰지 않는다. 선택형 자동 로그인은 **2단계**로 미룸(R9: `/api/login`은 JWT만 주고 쿠키는 `/api/session`이 따로 발급하므로 웹 앱에 토큰을 넣어 주는 브리지가 필요해 "최소 브리지" 원칙과 충돌). 세션 쿠키 7일 유지로 1단계는 충분.
- 캐시: 앱 전용 디렉터리(`filesDir`/`cacheDir`), 외부 저장소 사용 안 함. 내보내기 파일만 SAF로 사용자 지정 위치.
- WebView: JS 브리지는 origin이 설정된 NAS 주소일 때만 주입, 메서드 최소(내보내기 요청·진단 토글). `addJavascriptInterface` 대신 `WebViewCompat.addWebMessageListener`(origin 허용목록) 사용.
- 컴포넌트: `exported=false` 기본, 딥링크 없음.
- 공급망: 의존성 버전 고정, Dependabot, `gradle-dependency-check`(OWASP) CI 게이트, `detekt`+Android Lint 경고를 오류로.
- 검토 절차: 각 단계 완료 시 `docs/SECURITY-REVIEW.md`에 위협 모델(자산: 세션 쿠키·채팅 데이터·NAS 주소 / 위협: 단말 분실, 악성 앱, 중간자, 악성 서버 응답) 대비 통제 체크리스트를 채우고 미해결 항목을 명시.

---

## 4. 2단계: 번들 모드 (C안 안전판)

### 4.1 동작
1. 실행 시 `/api/update-check` → `currentVersion`.
2. `currentVersion == BUNDLED_UPSTREAM_VERSION`(APK 빌드 시 박힘)이면 가로채기 층이 `GET /`·`/assets/*`·루트 정적(`/manifest.json`, `/logo_*.png`, `/token/*` 토크나이저, wasm 등 `dist/` 전체)에 APK 자산을 응답. 서버가 주입하는 전역 `globalThis.__NODE__=true`, `__PATCH_SYNC__`는 번들 `index.html`에 동일하게 넣되, `__PATCH_SYNC__`는 서버 환경변수에 따라 달라지므로 **매 실행 서버의 `/` HTML에서 읽어 주입**한다(R8).
3. 다르면 가로채지 않음 → 서버 UI(= 1단계). 배너: "서버 v1.15 / 앱 번들 v1.14 — 서버 UI로 실행 중, 앱 업데이트 대기".
4. 버전이 같을 때만 포크 코드가 실행되므로 **저장 포맷 불일치 경로가 구조적으로 없음.**
5. 런타임 폴백: 번들 모드로 시작했는데 `index.html` 로드 실패, 렌더러 크래시, 30초 내 부트스트랩 미완료(`/api/read` 요청이 관찰되지 않음) 중 하나면 **그 세션은 서버 UI로 재시작**하고 AppLog에 `E/Bundle` 기록. 같은 번들 버전에서 2회 연속 실패하면 설정이 바뀔 때까지 번들 모드를 끈다.

판정 순서 요약: 서버 버전 읽기 → 번들 버전과 일치? → (예) 번들 UI 시도 → 실패 시 서버 UI / (아니오) 서버 UI. 1단계 APK에는 번들 모드가 없으므로 항상 서버 UI.

### 4.2 포크 관리
- `upstream/` 서브모듈이 아니라 **upstream 태그 + `patches/*.patch`**(git format-patch). 브랜치 분기 금지. 패치 0개로도 번들 모드는 성립해야 한다(순수 upstream 빌드).
- 패치 후보(측정 후 우선순위): 삽화 `loading=lazy` + 썸네일→원본 교체, 긴 채팅 메시지 가상화, 삽화 12장 병렬 생성(`stableDiff.ts`), 홈 캐릭터 목록 지연 렌더링.
- CI(`upstream-sync.yml`, 매일): 새 upstream 태그 감지 → 체크아웃 → 패치 적용 → `pnpm build` + `pnpm test` → 성공 시 APK 빌드·릴리스(`BUNDLED_UPSTREAM_VERSION` 갱신) / 실패 시 이슈 자동 생성(리베이스 필요).
- 실측 근거: 최근 3개 릴리스 중 2개(v1.12, v1.13)가 저장 포맷·동기화 계약을 변경. "작은 업데이트"는 1/3 수준이므로 자동 폴백이 필수.

### 4.3 운영 순서
앱 릴리스가 나온 뒤 NAS를 `docker compose pull` 하면 번들 모드가 끊기지 않는다. 순서가 바뀌면 원격 모드로 계속 사용 가능.

---

## 5. 기술 선택 (가정)

| 항목 | 선택 | 이유 |
|---|---|---|
| 언어/빌드 | Kotlin, Gradle KTS, AGP 최신 안정 | 표준 |
| minSdk / target | 33 / 최신 | S7+ = Android 13 최종 |
| HTTP | OkHttp 4 | 쿠키·스트리밍·캐시 제어 용이 |
| 캐시 | `com.jakewharton:disklrucache` | 단순·검증됨 |
| msgpack | `org.msgpack:jackson-dataformat-msgpack` | 스트리밍 파싱 |
| 백그라운드 | WorkManager + 포그라운드 서비스 | 프리페치/생성 유지 |
| 생체인증 | `androidx.biometric` | 표준 |
| 이미지 변환 | Android `Bitmap` + WebP 인코더(API 30+ 무손실/손실 지원) | 외부 라이브러리 불필요 |
| 서명 | 자체 keystore(RSA 4096, 유효 30년) → GitHub Secrets(base64) | Play 미사용. **키 분실 = 업데이트 불가**이므로 오프라인 백업 2곳 안내 |
| 버전 | `versionName = X.Y.Z`(태그) / `X.Y.Z-dev.N+sha`(main push nightly). `versionCode = X*1_000_000 + Y*10_000 + Z*100 + N(0~99)` → nightly·정식이 교대로 나와도 항상 증가. 2단계부터 번들 upstream 버전은 `BuildConfig`로 별도 표시 | 업데이트 판정·호환 표시 |
| 빌드 채널 | main push → `nightly` 프리릴리스(서명, `make_latest:false`) / `v*` 태그 → 정식 릴리스 | 테스트 빌드를 바로 받되 앱 내 업데이트는 정식만 따름 |

---

## 6. 저장소 구조

```
PocketRisu_app/
├─ app/                      # Android 앱 (모듈 3.1~3.9)
├─ docs/
│  ├─ DESIGN.md              # 이 문서 (설계 변경 시 함께 갱신)
│  ├─ LOGGING.md             # 로그 태그·마스킹 규칙·내보내기 형식
│  ├─ TROUBLESHOOTING.md     # 문제 → 원인 → 해결 (번호 붙여 누적)
│  ├─ WORKLOG.md             # 날짜별 작업·검증 명령·결과
│  ├─ SECURITY-REVIEW.md     # 단계별 위협 모델·체크리스트
│  └─ MEASUREMENTS.md        # S7+/S26 전후 실측
├─ patches/                  # [2단계] upstream 대상 패치
├─ upstream.lock             # [2단계] 번들한 upstream 태그
└─ .github/workflows/
   ├─ build.yml              # PR·push: lint, detekt, 단위 테스트, 의존성 취약점 스캔
   ├─ release.yml            # 태그 push: 서명 APK + sha256 → GitHub Release
   └─ upstream-sync.yml      # [2단계] upstream 릴리스 추종
```

---

## 7. 단계별 마일스톤

### 1단계 (B 범위) — 사용 가능한 앱
1. 스캐폴드 + Shell + **AppLog·진단 화면·로그 내보내기** + 서명·`release.yml` (설치·자체 업데이트 경로를 **먼저** 뚫어 이후 빌드를 바로 태블릿에서 검증하고, 그 결과를 로그로 확인)
2. InterceptLayer **tee + 계측**(`/api/read`, `/api/chat-content`, `/api/asset`: 첫바이트·총 시간·바이트·WebView HTTP 캐시 히트 여부) → `MEASUREMENTS.md` 0차 실측(S7+/S26: 홈 로드, 채팅 열기, 삽화 표시). **측정 없이는 다음 캐시를 만들지 않는다.**
3. AssetCache(stale-while-revalidate, Range, compress 무효화) + RisuSave ver7 디코더 + Prefetcher(트리거 A·B) → 1차 실측
4. GenWatcher(model-jobs + `/proxy2`, 포그라운드 알림) + 분할화면 검증
5. Updater(GitHub Releases, SHA-256·서명 검증, PackageInstaller) — **구현됨 2026-10-16 (실기기 설치 흐름 검증 대기)**, 설정 화면 포함
6. Exporter(단일 HTML, 리사이즈·미리보기·예상 용량)
7. AppLock, TOFU 핀
8. (측정이 정당화할 때만) DbBlobCache
9. `SECURITY-REVIEW.md` 1차 + CI 게이트

### 2단계 (C 범위) — 측정 결과가 요구할 때
10. 번들 모드 + 버전 게이트 + 런타임 폴백 (패치 0개로 upstream 빌드 번들)
11. `upstream-sync.yml` 자동 추종 파이프라인
12. 렌더링 패치(측정 상위 병목부터; 삽화 lazy는 1단계 캐시로 해소됐을 가능성이 커서 재측정 후 결정)
13. 자동 로그인(옵션), 프리페치 트리거 C(토큰 갱신 포함)
14. 내보내기 "화면 그대로"·ZIP 옵션

### 착수 판단 기준
2단계는 1단계 실측(`MEASUREMENTS.md`)에서 **WebView 내부 시간(디코드·렌더)이 전체의 절반 이상**으로 남을 때 시작한다. 네트워크·캐시가 병목이면 1단계 안에서 해결한다.

---

## 8. 설계 검토 반영 이력

2026-10-16 독립 검토(PocketRisu v1.14.0 소스 대조, 9건)를 반영했다. 본문에 `R<n>`으로 표시.

| # | 심각도 | 지적 | 반영 |
|---|---|---|---|
| R1 | blocker | `/api/read`·`chat-content`는 쿠키가 아니라 `risu-auth` JWT 인증 | 가로채기 층이 요청 헤더를 그대로 전달; 토큰 보관 안 함; 프리페치 C 2단계로 |
| R2 | blocker | chat-content 캐시 폴백은 적용 대상이 거의 없고 데이터 덮어쓰기 경로 생성 | ChatCache 삭제 → tee+계측만 |
| R3 | should-fix | db.bin ETag 캐시는 전송량만 줄이고 서버 인코딩·클라이언트 디코드는 그대로 | 측정 후 조건부(M8) + `x-db-etag` 에코 규칙 |
| R4 | should-fix | `/api/asset`은 같은 id로 내용이 바뀜, 미디어 Range | stale-while-revalidate, 206, compress 무효화 |
| R5 | should-fix | 삽화는 `/proxy2`로 가므로 model-jobs 감시로는 못 봄 | GenWatcher에 `/proxy2` 관찰 추가 |
| R6 | should-fix | RISUSAVE 블록 포맷 존재, 서버는 ver7만 사용 | ver7만 지원, 나머지 비활성 |
| R7 | nice | SW 간섭 없음, WebView HTTP 캐시 상한이 삽화 재다운로드 원인 | 계측에 HTTP 캐시 히트율 비교 추가 |
| R8 | nice | 번들 모드: 루트 정적 파일 포함, `__PATCH_SYNC__` 매번 읽기 | §4.1 수정 |
| R9 | nice | 렌더러 우선순위는 서비스와 별개, 자동 로그인은 브리지 필요 | IMPORTANT 유지, 자동 로그인 2단계 |

## 9. 열린 질문 / 가정

- **가정**: 내보내기 1단계는 CBS·정규식 미적용 원문 기반. 표시 결과와 다를 수 있음.
- **가정**: 프리페치가 서버 디스크 I/O에 주는 부담은 동시 4·생성 중 정지로 충분. NAS 모델에 따라 조정.
- **확인 필요**: Synology 역프록시의 요청 본문 상한(기본 설정이면 `database.bin` 전체 쓰기가 막힐 수 있음 — PocketRisu의 80MB 경고와 별개로 프록시 쪽 한계). 앱과 무관하지만 홈 100MB 문제의 근본 완화는 **캐릭터 비활성화(archive) 기능**이므로 병행 권장.
- **확인 필요**: 자체 설치 후 `USER_ACTION_NOT_REQUIRED`가 One UI에서 실제 적용되는지(기기별 실측).
- **확정**: 자동 로그인은 2단계(기본 off), 캐시 상한 기본 10GB.
