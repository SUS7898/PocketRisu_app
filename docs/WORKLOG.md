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

### 미검증 / 다음
- T-11 실기기 확인(세로/가로/키보드/분할 화면).
- 사용자 조치 권고: WygLore 옛 데이터 정리, Wi-Fi에서 내부 주소/Tailscale로 접속 비교.
- dev.5 로그에서 `POST write key=database/database.bin len=…` 또는 `[Save] Patch hash mismatch` 확인 → 전체 쓰기 가설 확정.
- T-7 서버 로그 확인(사용자: `docker logs --since 5m risuai-nodeonly`).
- 번들 모드 실기기: 로그에 `mode=bundled v1.14.0 patchSync=…` → `I/Bundle healthy` 가 나와야 함. 안 나오면 `E/Bundle fallback …` 사유 확인.
- 캐릭터 첫 진입 53초의 정체 — 새 nightly로 같은 동작 반복 후 로그 (키 이름 포함).
- 실기기에서 설정 → 업데이트 확인 → 설치까지의 흐름(특히 `USER_ACTION_NOT_REQUIRED`가 One UI에서 적용되는지) — nightly 2개가 쌓이면 테스트 가능.
- S7+ 설치·로그 — 사용자 확인 필요.
- 마일스톤 2: InterceptLayer tee + 계측 → `MEASUREMENTS.md` 0차 실측.
