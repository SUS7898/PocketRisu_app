# 로그·진단 규칙

앱의 모든 로그는 `AppLog`를 거친다. `android.util.Log` 직접 호출 금지(Lint 규칙으로 막음 예정).

## 1. 형식

```
2026-10-16 13:40:12.345 I/Shell  WebView ready (chromium 140.0.7339.51)
<날짜 시각> <레벨>/<태그> <메시지>
```

| 레벨 | 용도 |
|---|---|
| D | 개발 중 추적. 릴리스 빌드에서는 링버퍼에만 남고 파일에는 쓰지 않음 |
| I | 상태 변화(모드 결정, 캐시 히트/미스 집계, 내보내기 시작/완료) |
| W | 자동 복구한 문제(재시도 성공, 폴백 작동) |
| E | 실패(사용자에게 보이는 오류, 폴백도 실패) |

## 2. 태그 = 모듈

`App` `Shell` `Setup` `Intercept` `Cache` `Prefetch` `RisuSave` `Export` `Update` `Lock` `Gen` `Net` `Diag`

## 3. 마스킹 — 기록 시점에 적용

`Redactor.redact(message)`가 모든 메시지에 자동 적용된다. 내보낼 때 거르는 방식이 아니므로, 메모리·파일 어디에도 원문이 남지 않는다.

| 대상 | 규칙 | 결과 |
|---|---|---|
| `risu-auth`, `Authorization`, `Cookie`, `Set-Cookie`, `x-session-id` 헤더 값 | 헤더명 뒤 값 전체 | `risu-auth: <redacted>` |
| JWT (`xxx.yyy.zzz` base64url 3분절, 각 20자 이상) | 패턴 치환 | `<jwt>` |
| `password`, `passwd`, `pwd`, `token`, `secret`, `apikey`, `api_key` = 값 (쿼리/JSON/폼) | 키는 남기고 값 치환 | `password=<redacted>` |
| `risu-session=` 쿠키 값 | 값 치환 | `risu-session=<redacted>` |
| URL 쿼리의 `risu-auth=` | 값 치환 | |
| 채팅 본문, 메시지 텍스트, 캐릭터·채팅 이름, 프롬프트 | **로그에 넣지 않는다** (코드 규약; 길이·개수·ID만 기록) | `chat-content chaId=ab12… msgs=412 bytes=1.2MB` |
| 서버 호스트명 | 기록은 하되 **내보내기 시** `<server>`로 치환. 진단 화면 토글 "서버 주소 포함"으로 해제 가능 | |
| 이미지/에셋 키(hex) | 민감 아님. 앞 12자만 기록해 길이 절약 | `asset 696e6c61792f…` |

ID(`chaId`, `chat.id`, inlay id)는 그 자체로는 내용을 드러내지 않으므로 기록한다. 캐시 키 대조·문제 재현에 필요하다.

## 4. 저장

- 메모리 링버퍼: 최근 2,000줄 (진단 화면 tail 표시).
- 파일: `filesDir/logs/app-0.log` … `app-4.log`, 512KB 넘으면 회전. 앱 전용 영역이라 다른 앱이 읽을 수 없음.
- 삭제: 진단 화면 "로그 지우기", 또는 앱 데이터 삭제.

## 5. 내보내기 파일 형식

```
# PocketRisu App diagnostics
app: 0.1.0 (code 1000) release
device: samsung SM-T970 / Android 13 (API 33)
webview: com.google.android.webview 140.0.7339.51
server: <server> version 1.14.0 (deploymentType=docker) mode=server-ui
settings: cacheLimit=10GB prefetch=on autoLogin=off lock=off tofuPin=on
exported: 2026-10-16T13:45:00+09:00
----
<로그 본문, 오래된 것부터>
```

출력 경로: SAF(`ACTION_CREATE_DOCUMENT`, `text/plain`) 또는 공유 시트(FileProvider, `cacheDir/share/`에 임시 복사 후 공유, 다음 실행 시 삭제).

## 6. 확인 포인트 (사용자가 로그에서 볼 것)

| 확인하고 싶은 것 | 찾을 줄 |
|---|---|
| 앱이 서버에 붙었나 | `I/Setup server reachable status=... version=...` |
| 어떤 모드로 떴나 | `I/Shell mode=server-ui` (2단계: `mode=bundled v1.14.0`) |
| 홈 100MB를 다시 받았나 | `I/Cache db.bin etag=... hit=304` vs `miss=200 bytes=...` (마일스톤 2부터) |
| 삽화가 캐시에서 나왔나 | `I/Cache asset hit ...` / `miss ...` 와 세션 요약 `hit=118 miss=12` |
| 채팅이 왜 안 열렸나 | `W/Intercept chat-content first-byte timeout 8000ms → cache fallback` / `E/...` |
| 업데이트가 왜 안 됐나 | `E/Update sha256 mismatch` / `E/Update signer mismatch` |
