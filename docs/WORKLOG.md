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

### 미검증 / 다음
- 실기기(S7+, S26) 설치·실행·로그 내보내기 — 사용자 확인 필요.
- GitHub Secrets 등록(`pocketrisu-app-keystore/set-secrets.sh`) 및 첫 태그 릴리스 — 사용자 승인 후.
- 마일스톤 2: InterceptLayer tee + 계측 → `MEASUREMENTS.md` 0차 실측.
