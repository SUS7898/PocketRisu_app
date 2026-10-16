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

### 미검증 / 다음
- 실기기에서 설정 → 업데이트 확인 → 설치까지의 흐름(특히 `USER_ACTION_NOT_REQUIRED`가 One UI에서 적용되는지) — nightly 2개가 쌓이면 테스트 가능.
- S7+ 설치·로그 — 사용자 확인 필요.
- 마일스톤 2: InterceptLayer tee + 계측 → `MEASUREMENTS.md` 0차 실측.
