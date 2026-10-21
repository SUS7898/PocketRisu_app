# 작업일지

날짜별로 무엇을 했고, **어떤 명령으로 검증했고 결과가 어땠는지**를 남긴다. "됐다"는 말 대신 명령과 출력.

---

## 2026-10-16 — 설계 확정, 마일스톤 1 착수

### 결정
- 아키텍처: 1단계 B(WebView + 네이티브 가로채기 층), 2단계 C(번들 모드 + 버전 게이트 + 런타임 폴백). `docs/DESIGN.md`.
- 내보내기 단일 HTML, 삽화 캐시 상한+LRU, 앱 잠금 선택형.
- 로컬 프록시 → `shouldInterceptRequest`로 변경 (쿠키 SameSite·공격면 이유, DESIGN §2.1).
- 작업 규칙: 코드 변경 시 설계/트러블슈팅/작업일지 동반 갱신. 앱은 민감정보 마스킹된 로그를 내보낼 수 있어야 함 (`docs/LOGGING.md`).

### 조사 결과 (PocketRisu v1.14.0 소스)
- `/api/read`·`/api/chat-content` 응답 = `RISUSAVE` 매직(11B) + msgpack(+zlib/gzip). 앱은 읽기 전용 디코딩만.
- `/api/asset/:hex` = `immutable, max-age=1y` + ETag, 세션 쿠키 인증. `inlay_thumb/` 썸네일 엔드포인트 있음.
- 최근 3개 릴리스 중 2개가 저장 포맷·동기화 계약 변경 → C안 단독은 위험, 버전 게이트 필수.

### 작업
- 저장소 clone, Gradle 스캐폴드(AGP 8.10.1 / Kotlin 2.1.21 / Gradle 8.14.3, lanraragi-app과 동일 툴체인), WebView Shell, 설정 화면, AppLog + 마스킹 + 진단/내보내기 화면, 서명 설정, CI/Release 워크플로.

### 설계 검토
- 독립 검토(소스 대조) 9건 → DESIGN.md §8에 반영. 블로커 2건(인증 헤더, chat-content 폴백)은 소스에서 재확인 후 설계 변경: ChatCache 삭제, DbBlobCache 측정 후 조건부, 프리페치 C·자동 로그인 2단계로.

### 검증
| 명령 | 결과 |
|---|---|
| `.\gradlew.bat :app:testDebugUnitTest` | 11 tests, 0 failed (1차는 `RedactorTest` 기대값 오류 1건 → 테스트 수정) |
| `.\gradlew.bat :app:lintDebug` | 경고만(버전 업데이트 안내, UseKtx, SetTextI18n, MonochromeLauncherIcon), 오류 0 |
| `.\gradlew.bat :app:assembleDebug` | `app-debug.apk` 15.1MB |
| `.\gradlew.bat :app:assembleRelease` | `app-release.apk` 2.6MB. 1차는 디버그 키로 서명됨(T-3) → 수정 후 릴리스 키 서명 확인 |
| `apksigner verify --print-certs` | v3 true, `CN=PocketRisu App, OU=SUS7898`, cert SHA-256 `378a0f32…c6f4` |
| `aapt2 dump badging` | `com.sus7898.pocketrisu` 0.1.0 (1000), minSdk 33, targetSdk 35 |

### 실기기 1차 (S26 Ultra, Android 17, WebView 154)
사용자가 0.1.0 설치 후 내보낸 로그:
- `13:37:35 App start` → `13:38:01 Setup server reachable version=1.14.0 type=docker passwordSet=true` → `13:38:03 url saved` → `WebView ready` → `mode=server-ui` → `13:38:04.578 page finished path=/` (셸 오버헤드 0.8초). W/E 없음 → 셸은 정상.
- 머리의 `(version unknown)`는 T-4 버그 → 수정.
- 한계: 1단계 로그는 `/api/read`(100MB 부트스트랩)·채팅·삽화의 **소요시간을 재지 않는다**. "느린가?"는 마일스톤 2 계측 후 답할 수 있음. 이번 수정으로 `/api/read` 요청 시작 시각만 I 레벨로 추가.

### CI 변경 (사용자 요청: push로 APK 생성)
- `ci.yml`에 `nightly` 잡 추가: main push → `git describe` 버전 → 서명 릴리스 APK → `nightly` 프리릴리스 갱신(`make_latest:false`).
- versionCode 공식 변경: `X*1_000_000 + Y*10_000 + Z*100 + dev(0~99)` (`release.yml`도 동일). 기존 설치(0.1.0, code 1000) 위에 새 체계(code 10000+)가 올라감.

### push · Secrets · 첫 CI
- `main` push, `set-secrets.sh`로 Secrets 4개 등록(04:49Z), `v0.1.0` 태그 push(1차 408 네트워크 오류 → 재시도 성공).
- 첫 CI·Release 모두 `./gradlew: Permission denied` 실패 → T-5(실행 비트) 수정. 태그는 새 커밋으로 이동.

### 설정 화면 + 자체 업데이트 (DESIGN §3.6 = M5 선행)
- `SettingsActivity`: 서버 변경, nightly 채널 토글, 업데이트 확인 → 다운로드 → SHA-256(`SHA256SUMS.txt`) → 서명 인증서 동일성 → `PackageInstaller`. "알 수 없는 앱 설치" 미허용이면 시스템 설정으로 안내.
- `MainActivity`: 하루 1회 자동 확인 → 토스트만(설치는 수동).
- `VersionCode.kt`는 Gradle 공식의 거울; `VersionCodeTest`로 nightly↔정식 순서 보장 검증.

### 검증 (2차)
| 명령 | 결과 |
|---|---|
| `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease` | BUILD SUCCESSFUL (테스트 13건, Lint 오류 0) |
| `--% -PappVersionName="0.1.0-dev.3+abc1234"` | `versionCode=10003`, `versionName=0.1.0-dev.3+abc1234` (aapt2 badging) |

### CI 2차 (실행 비트 수정 후)
- CI run 38853092012 success (6m25s): 테스트·Lint·디버그 APK + nightly 잡 → `nightly (0.1.0)` 프리릴리스 발행.
- Release run 38853104614 success (3m27s): Trivy 통과, 서명 검증(v3) 통과 → `v0.1.0` 릴리스(Latest) 발행.
- 이 WORKLOG 커밋을 push하면 nightly가 `0.1.0-dev.1+<sha>`(code 10001)로 갱신되어, 설치된 v0.1.0(code 10000) 위에 앱 내 업데이트(nightly 채널)를 시험할 수 있다.

### 실기기 2차 (S26 Ultra, v0.1.0 code 10000) — "빈 채팅방인데 캐릭터 첫 진입이 느림"
로그 타임라인:
- `14:21:49.2 page finished` → `14:21:54.6` 첫 `/api/read` (로그인 후 부트스트랩 시작으로 추정) → **60초 공백** → `14:22:54` 10건 버스트(5건은 같은 ms = 병렬 소형 읽기).
- `14:25:04` 9건을 50ms 간격으로 + 플러그인 샌드박스 오류 2건(`API method toast not found (about:srcdoc)` = API v3 플러그인이 PocketRisu에 없는 `toast`를 호출, 무해).
- `14:26:26 → 14:27:19` **12건이 3~4초 간격으로 직렬** = 약 53초. 캐릭터 진입 구간으로 추정.
해석의 한계: M1은 `/api/read`를 모두 "bootstrap"으로 찍었지만 실제로는 **범용 KV 읽기**(`file-path` 헤더에 키). 소스상 `/api/read`로 바이트를 읽는 곳은 부트스트랩(`database/database.bin`), `readImage()`(process/index, characterCards, persona), **플러그인 API v3 `getItem('assets/…')`** 이다. 표시용 이미지는 `/api/asset`(HTTP 캐시)로 가므로, 3~4초 간격 직렬 12건은 플러그인 또는 처리 로직이 큰 에셋을 **순차로 통째 읽는** 패턴일 가능성이 높다. 키가 없어 확정 불가 → `RequestObserver`로 키 접두사·chaId·델타 여부를 기록하도록 변경(이 커밋). 다음 로그에서 `read key=assets/ …`가 12건 직렬로 보이면 확정.
- 셸 자체는 정상(`mode=server-ui`, W/E 없음). 업데이트 확인도 동작(`check channel=STABLE … newer=false`).

### 실기기 3차 — 자체 업데이트 성공
- `0.1.0(code 10000)` → nightly `0.1.0-dev.1+267ecc1(code 10001)` 앱 내 설치 성공 (`I/Update install success`가 새 버전 첫 실행 시 기록됨 = 설치 리시버가 재시작 후 수신). nightly 채널 확인 `newer=false` 정상.
- `WebView ready → page finished` 37초 (1차 0.8초). 업데이트 직후 재시작이라 WebView 콜드 스타트/서버 응답 중 어느 쪽인지 미확정. 번들 모드에서는 `index.html`·JS가 로컬이라 이 구간이 사라지므로, 다음 로그로 비교.

### C(번들 모드) 구현 — 사용자 지시로 2단계 착수
- `scripts/build-frontend.sh` + `upstream.lock`(v1.14.0) + `patches/`(0개). 로컬 실행: 233 files, 70,775KB. 필요 도구 `pnpm@10.34.1` 전역 설치(`npm i -g`).
- 앱: `bundle/` 패키지(BundleRepository·UiMode·ServerIndexProbe·BundleServer), MainActivity 모드 결정·폴백·health, 설정 토글, 진단 머리.
- CI: ci.yml(build·nightly)·release.yml에 Node 24 + pnpm + 번들 빌드 단계. `upstream-sync.yml` 신규.
- 발견: Kotlin 블록 주석 안의 `/api/*`는 **중첩 주석 시작**이라 컴파일 오류("Unclosed comment") — 주석에는 `/api/…`로 쓴다.

### 검증 (3차)
| 명령 | 결과 |
|---|---|
| `UPSTREAM_DIR=… SKIP_INSTALL=1 PNPM=pnpm bash scripts/build-frontend.sh` | 233 files, 70MB, `BUNDLE.json` 생성 |
| `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease` | BUILD SUCCESSFUL (테스트 19건), `[PocketRisuApp] bundled UI upstream v1.14.0`, `app-release.apk` 26.4MB |

### 실기기 4차 (dev.2, RequestObserver 키 포함) — 채팅 열기 실패 원인 특정
- 부트스트랩 `database/database.bin` → 홈 표시까지 **13초**(S26). 플러그인 저장소 6건 읽기 후 chat-content 요청이 **정확히 30.000초 뒤 재시도** = 클라이언트 첫 바이트 타임아웃. 다른 채팅은 즉시 열림 → 서버가 그 시점에 30초+ 막혀 있었음. 상세·가설·확인 방법은 T-7.
- 첫 패치 `patches/010-chat-first-byte-timeout-120s.patch` 작성(번들 모드 전용). `build-frontend.sh`로 적용·빌드 검증.

### 실기기 5차 (dev.4, 번들 모드 첫 실행) — **C 모드 동작 확인**
- `mode=bundled v1.14.0 patchSync=true` → `I/Bundle healthy (served 17 bundle files)` → `page finished` **0.2초**(서버 UI 모드 0.8~37초). 부트스트랩→홈 16초(서버 UI 13초와 같은 수준 = 데이터 시간).
- 자체 업데이트 dev.2→dev.4(26MB): 1차 `INSTALL_FAILED_ABORTED … unknown source package`(T-8), 2차 성공. `USER_ACTION_NOT_REQUIRED` 제거.
- 채팅 열기: 패치 010대로 120초 대기 후 재시도, 재시도는 25초 뒤 `Failed to fetch`. 요청 3초 전 두 번째 `database.bin` 전체 읽기 관찰 → 전체 쓰기(full write) 가설(T-7 추가 증거). 쓰기 경로·`[Save]` 콘솔 경고 기록 추가.

### 실기기 6차 (dev.5) — 실행 정지·설치 검증·플러그인 발견
- 실행 후 44초 빈 화면 → `mode=server-ui (server version unknown)`: 블로킹 프로브가 타임아웃을 무력화(T-9). `Call.await()` + `probeClient`로 교체, 서버 못 닿으면 캐시된 버전으로 번들 시작 후 백그라운드 재확인.
- 업데이트 첫 시도 `INSTALL_FAILED_VERIFICATION_FAILURE`, 두 번째 성공(T-10). 안내 토스트 + APK 재사용.
- 채팅 3개 모두 재시도 없이 열림. 전체 업로드(`write database.bin`) 없음 → T-7 가설 보류. 대신 ① 직전 4분 서버 연결 불가(ConnectException, 공인 IP 경로) ② WygLore 플러그인 78MB+ 옛 페이로드 경고 발견.
- 테스트: 19건 통과, Lint 오류 0, assembleRelease 성공.

### 휴대폰 스크린샷 — 시스템 바 겹침 (T-11)
- 웹 UI가 상태 표시줄·제스처 바 밑에 깔림 → 루트에 systemBars+displayCutout+ime 인셋 패딩. 키보드 겹침도 같이 해결. 보조 화면은 `fitsSystemWindows`.

### 실기기 7차 (522KB 로그, dev.5~dev.7) — 백그라운드 복귀 시 재접속 (T-12), 플러그인 읽기 폭주 (T-13)
- `renderer gone crashed=false priority=2` + 앱 프로세스 재시작 2회 → `KeepAliveService`(specialUse 포그라운드) + 배터리 최적화 예외 버튼 + PocketRisu `nodeOnlyRestoreLastChat` 안내.
- `wl.web.v1` 6,370회/10분 읽기 폭주(WygLore Leaf 3.1.0) 발견. 230회 patch, db.bin 전체 쓰기 0회, `[Save] Patch conflict with a foreign revision, rebasing` 1회(다른 기기/탭 동시 사용?).
- 번들 모드 재시작 2회 모두 `healthy`, page finished 0.2~0.4초.

### 실기기 8차 (592KB 로그, dev.7→dev.8) — 채팅 지연의 결정적 패턴, 동결 아티팩트
- 4개 세션 대조: `/api/db/flush` **뒤**의 첫 채팅 열기만 120초 무응답, **앞**의 것은 즉시. flush = 클라이언트가 `visibilitychange: hidden`마다 보내는 "전체 DB 저장+백업" 강제 요청 → 패치 020으로 번들 모드에서 생략(T-7 결론).
- 67분 백그라운드 후 복귀 시 asset-manifests 타임아웃 253건 동시 발생 = 캐시 앱 동결 아티팩트(T-14). keep-alive가 해결해야 함.
- dev.8 첫 실행: `KeepAlive started` → `bundled` → `healthy`. 설치 재시도 시 `reusing downloaded` 동작 확인.
- 번들 재빌드: 패치 2개 적용.

### 실기기 9차 (Tab S7+, dev.9) — 첫 태블릿 로그
- 번들 모드·keep-alive·알림 권한 정상. 부트스트랩→홈 14초. 채팅 열기 재시도 없음, `/api/db/flush` 0건 → **패치 020 효과 확인**.
- 20:06:57 MainActivity 0.3초 간격 2회 재생성(프로세스 유지) → 페이지 재로드(T-15). `RetainedSession`으로 WebView를 Activity 재생성에서 살리고 `configChanges` 확대.

### 실기기 10차 (Tab S7+, dev.9, 실제 채팅 1턴 전체) — 시간 분해
| 구간 | 로그 | 소요 |
|---|---|---|
| 메인 모델(Gemini 3.1 Pro, 서버 job) | `POST model-jobs` 20:15:22 → `DELETE` 20:16:07 / 20:16:14 → 20:17:19 | **45초 / 65초** |
| 보조 모델(3.8 Flash) | `[ModelJob] job creation rejected (400), falling back to direct request path` → `POST /proxy2` 20:18:20 → 첫 NAI 호출 20:19:12 | **≈52초** (서버 job 거부 → 브라우저 직접 경로) |
| 삽화 12장(NAI) | `/proxy2` → `inlay_meta` 읽기 → `inlay`·`inlay_info`·`inlay_meta` 쓰기, 장당 ≈5.5초 **직렬** | **68초** (20:19:12 → 20:20:20) |
| 채팅 열기 3회 | 각 1회 요청, 재시도 없음, `/api/db/flush` 0건 | 즉시 |
| 부트스트랩→홈 | 19:53:33 → 19:53:47 | 14초 |
- 1턴 합계 ≈ 165~185초 = 사용자가 말한 "90~180초"의 정체. 앱이 건드릴 수 있는 건 삽화 직렬 68초(병렬화 패치 후보)와 부트스트랩 다운로드 부분뿐; 모델 시간(45~65 + 52초)은 Gemini 응답 시간.
- 보조 모델이 서버 job에서 400으로 거부돼 **브라우저 직접 경로**로 간다 → 이 구간은 앱이 백그라운드로 가면 끊긴다(keep-alive가 중요한 이유). 왜 400인지는 PocketRisu 쪽 확인 필요(Vertex 서비스계정 모델?).
- 블롭 다운로드 실패 1건(T-16) → `DownloadBridge` + 패치 030.
- 기타: Lua 스크립트 오류(`secondLastChat` nil), Lightboard 플러그인 출력 처리 실패, 플러그인 저장소 읽기 ≈8/분(WygLore, 폭주는 아님), `drafts/` 초고 자동저장 분당 ~20회.

### 삽화 병렬화 조사 — 패치 불필요
- 12장은 PocketRisu 코어가 아니라 **Lightboard 모듈(lb-xnai)** 의 Lua가 돌린다: `xnai.gen.lua` → `lb--be/lorebooks/image.lua generateImageFromPrompts` → PocketRisu Lua API `generateImage()`(`scriptings.ts:409`, 뮤텍스 없음) → `generateAIImage` → `/proxy2`(NAI) → `writeInlayImage`.
- Lightboard `lb--be/triggers/pipeline.lua runGenerationBatch`가 **이미 동시 실행을 지원**: 전역 변수 `toggle_lightboard.concurrent`(1~5, 기본 1)만큼 워커를 띄움. 사용자 설정이 1이라 직렬이었음. → 앱 패치(040) 대신 **모듈 설정에서 2~3으로** 올리면 됨. NAI 429가 나면 1~2로.
- 저장 구조 재확인(사용자 질문): 채팅 생성/편집은 `POST /api/chat-content …`(서버가 `queueStorageOperation` 안에서 즉시 저장), 그 외 변경은 `POST /api/patch`(메모리 반영 → 5초 뒤 디스크 + 백업 회전), 임포트·이미지는 `POST /api/write`(즉시). 패치 020이 뺀 것은 "화면 전환 때 5초 타이머를 지금 당장 돌려라"뿐.

### 실기기 11차 (Tab S7+, dev.9) — 삽화 오류 뒤 새로고침
- 20:58:28 `[Lightboard] sideEffect 출력 처리 실패 … 응답이 비어있습니다. 검열?`(모듈 오류, 앱 무관) → 20:59:18 `KeepAlive started` → `state restored` → 재부트스트랩. `App start` 없음 = T-15 재발(dev.9에는 수정 미포함). 원인 필드를 잡기 위해 `onConfigurationChanged` diff 로깅 + "activity recreated" 로그 추가.

### 실기기 12차 (Tab S7+, dev.12, 21:12 → 익일 04:41, 279KB)
- **T-15 원인 확정**: `configuration changed in place: keyboard,keyboardHidden,navigation` 9회 = 키보드 커버 탈착. 모두 제자리 처리, 재생성 0회, `App start` 0회(설치 시 제외). 해결.
- `/api/db/flush` 0건, 채팅 열기 재시도 0건 유지.
- **삽화는 여전히 직렬**(장당 4~11초, 12장 64~93초): Lightboard `concurrent`는 모듈(manifest) 간 동시성이고, xnai는 `xnai.onOutput.lua`에서 슬롯을 `for … pcall(gen.generate)`로 **순차 호출**한다. 따라서 설정값으로는 안 풀리고 xnai 모듈 Lua 수정(모듈 제작자) 또는 PocketRisu `generateImage` 지연-인레이 패치가 필요. 후자는 파서의 미존재 인레이 처리 확인이 선행돼야 해 보류.
- 메인 모델 job 400 → CORS → proxy 실패 → 재시도 체인으로 턴당 1~3분 허비(T-18). 앱 밖.
- 클립보드 복사 거부(T-17) → `ClipboardBridge`.

### T-18 확정 — 요청 로그 `{"error":"No auth header"}`
- 메인 모델 job 400의 정체는 Vertex가 아니라 **빈 `risu-auth`**. `authFetch`가 인증 오류 후 `cachedJwt`를 비우면, `createAuth()`를 직접 쓰는 job/proxy/Vertex 토큰 호출이 `''`를 받아 실패. 서버 정지(54~167초) 뒤 처리되며 JWT(5분)가 만료된 것이 방아쇠.
- 패치 040: 갱신 여유 30초→2분, 토큰 없으면 `/api/test_auth`(세션 쿠키)로 즉시 재발급. 번들 패치 4개.

### 실기기 13차 (Tab S7+, dev.14, 05:15 → 09:35)
- 패치 040 작동: 400 1회 → `JWT re-issued` 즉시 → proxy 성공, `[ModelPreset] request failed` 0건.
- 남은 것: 서버 정지 162초(05:44:42 job POST → 05:47:24 응답). 서버 소스상 5분 간격 백업(`BACKUP_INTERVAL_MS`) 복사와 시각·길이가 일치 → T-18에 사용자 조치(env로 간격 확대) 기록. 여유 2분→4분(dev.15).
- 삽화 12장: **48초 / 41초**(이전 64~93초; NAI 응답 자체가 빨라진 시간대). 여전히 직렬.
- 서버 연결 실패로 캐시 버전 번들 시작 → healthy (T-9 경로 정상). 키보드 구성 변경 6회 제자리 처리. `renderer gone` 0, flush 0.
- Lightboard "응답이 비어있습니다. 검열?" 1회(Gemini 빈 응답, 앱 무관). 복사(T-17)는 아직 사용 흔적 없음.

### upstream v1.15.0 + 번들 패치 050·060 (2026-10-20)
- 서버를 v1.15.0으로 올린 뒤 로그: 409 `modelJobBusy`·토큰 5xx HTML 0건(v1.12.0 #87 + DSM 프록시 read timeout 600초). `[Storage] No response headers within 120000ms` 10/16 220건·10/18 397건은 각각 **한 초에 216건·387건** 몰린 버스트 — T-14(캐시 앱 동결 복귀 아티팩트) 패턴이지 서버 정지가 아님. 10/19 0건은 keep-alive 효과. (처음엔 v1.15.0 #102 에셋 스윕과 연결했으나 버스트 분포로 철회.)
- 앱은 `mode=server-ui (version mismatch: server 1.15.0, bundle 1.14.0)` 폴백 → `upstream.lock` v1.15.0, `VERIFIED_SERVER_VERSIONS`에 1.15.0 추가.
- **패치 050** `ChatBody.svelte`·`parser.svelte.ts`: 삽화 생성 직후 이미지가 빈 채로 남고 채팅을 나갔다 와야 보이던 문제. `await tick()` 뒤 플레이스홀더 조회가 0개면 `requestAnimationFrame`에서 재시도(`{:then}` 교체와 effect의 `.then()`이 별개 마이크로태스크). 같은 함수의 IntersectionObserver가 저장소 8곳 중 유일하게 `disconnect()`가 없어 함께 정리. upstream 단위 테스트 8/8.
- **패치 060** `request.ts`: `ModelPresetAdapterError.retryable === false`(auth/404/invalid-request/차단)에 `noRetry` — 지금까지 로그에서만 읽혀 같은 요청이 2회 더 나갔음. 비스트리밍 빈 텍스트 + 비정상 `finishReason`(SAFETY/MAX_TOKENS/RECITATION/…)은 사유를 담은 실패로 반환 → Lightboard "응답이 비어있습니다. 검열?" 추측 대신 이유가 보임. upstream `pnpm check` 0 errors, request 경로 테스트 174/174.
- 두 패치 모두 v1.14.0·v1.15.0 태그에 `--3way` 없이 깨끗이 적용됨.
- 서버 쪽(`server.cjs` 토큰·hub-proxy 타임아웃)은 번들 범위 밖 — 로컬 PocketRisu 클론 브랜치 `fix/bounded-fetches-and-empty-responses`에 보관, 컨테이너를 직접 빌드할 때만 적용.
- 같은 날 upstream **v1.15.1** 핫픽스(#104, Vite 8.3.4 — Termux ARMv8.0 빌드). 소스 변경 없이 `package.json`·lockfile만 → lock을 v1.15.1로, 패치 6개 그대로 적용. 서버는 v1.15.0에 머물러도 동작 차이 없음(번들 모드만 서버 버전 == 번들 버전 조건이라, 서버를 1.15.1로 올려야 켜짐).

### dev.20 로그 (10/21) — 캐릭터 가져오기 무반응, upstream v1.15.2
- 번들 모드 정상: `mode=bundled v1.15.1 patchSync=true` → `Bundle healthy`. 409·토큰 HTML 0건.
- **T-20**: 가져오기에서 파일을 골라도 무반응. 로그에 선택·쓰기 흔적 없음 → `FileChooserParams.parseResult`가 `ClipData`를 무시해 `null`을 돌려주는 경로. `parseFileChooserResult()`로 교체 + 선택창 열림/결과 로그 추가. 실기기 재현 로그로 확정 필요.
- `SyntaxError: Invalid regular expression: /<img="(?!(?:(?:shiro|…` 162건(10/20 23:15~): 캐릭터 `f008a40d`를 열 때마다 그 카드의 정규식 스크립트가 컴파일 실패(`scripts.ts`가 플래그 없으면 `u`를 붙임 — `u` 모드에서 이스케이프 안 된 `{`·`-` 등이 에러). 스크립트는 건너뛰어지고 채팅은 동작하므로 가져오기 문제와 무관. 로그 줄이 384자에서 잘려 정확한 사유는 불명 — 카드의 정규식 `in`을 직접 확인해야 함.
- upstream **v1.15.2**(PR #107, Lua 엔진 옵션 1줄): 계약 파일 변경 0, 패치 6개 그대로 적용. lock → v1.15.2.
- **T-21**: 모듈 가져오기에서 `.risum`이 회색 비활성. `createIntent()`가 확장자 accept를 MIME으로 넘김 → `fileChooserIntent()`로 교체(진짜 MIME만, 아니면 `*/*`). T-20과 합쳐 `FileChooserParams` 기본 헬퍼 의존 0.

### 미검증 / 다음
- T-20: 다음 nightly로 가져오기 재현 → `file chooser result code=-1 uris=N` 뒤에 쓰기 요청이 이어지는지.
- 패치 050: 실기기에서 삽화 생성 직후 스크롤·채팅 이동 없이 이미지가 뜨는지. 로그엔 안 남으니 육안 확인.
- 패치 060: Lightboard 빈 응답 재발 시 `Model returned no text (finishReason: …)` 문구가 뜨는지 → SAFETY면 프롬프트, MAX_TOKENS면 maxOutputTokens/thinking 조정.
- 서버 env `POCKETRISU_BACKUP_INTERVAL_MS` 확대 후 정지 재발 여부.
- dev.15: 4분 여유로 정지 중 400 소멸 여부, `[Storage] JWT re-issued from the session cookie` 출현 여부.
- T-17: 복사 동작 확인. T-18: PocketRisu 요청 로그에서 400 본문 확인 → 제보.
- Lightboard `concurrent` 2~3 설정 후 12장 소요 비교(68초 기준).
- 삽화 병렬화 패치(040) — Lightboard 설정으로 대체, 보류: NAI 동시 2~3장, 429 시 직렬 복귀.
- T-15: 태블릿에서 키보드 커버 탈착·회전·분할 화면 뒤 `WebView re-attached` 로그와 함께 페이지가 유지되는지.
- dev.9 로그: `POST /api/db/flush` 부재 + 첫 채팅 열기 재시도 없음 확인.
- T-12: keep-alive 켠 상태에서 앱 전환 후 복귀 시 `renderer gone` 재발 여부.
- T-11 실기기 확인(세로/가로/키보드/분할 화면).
- 사용자 조치 권고: WygLore 옛 데이터 정리, Wi-Fi에서 내부 주소/Tailscale로 접속 비교.
- dev.5 로그에서 `POST write key=database/database.bin len=…` 또는 `[Save] Patch hash mismatch` 확인 → 전체 쓰기 가설 확정.
- T-7 서버 로그 확인(사용자: `docker logs --since 5m risuai-nodeonly`).
- 번들 모드 실기기: 로그에 `mode=bundled v1.14.0 patchSync=…` → `I/Bundle healthy` 가 나와야 함. 안 나오면 `E/Bundle fallback …` 사유 확인.
- 캐릭터 첫 진입 53초의 정체 — 새 nightly로 같은 동작 반복 후 로그 (키 이름 포함).
- 실기기에서 설정 → 업데이트 확인 → 설치까지의 흐름(특히 `USER_ACTION_NOT_REQUIRED`가 One UI에서 적용되는지) — nightly 2개가 쌓이면 테스트 가능.
- S7+ 설치·로그 — 사용자 확인 필요.
- 마일스톤 2: InterceptLayer tee + 계측 → `MEASUREMENTS.md` 0차 실측.
