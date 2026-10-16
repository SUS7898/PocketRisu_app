# 트러블슈팅

문제가 생기면 여기에 **번호를 붙여 누적**한다. 해결된 항목도 지우지 않는다(같은 증상이 다시 나오면 가장 빠른 길이 이 문서다).

형식:

```
## T-<번호>. <증상 한 줄>
- 발생: <날짜>, <환경: 기기/빌드/서버 버전>
- 증상: 무엇이 어떻게 보였나 (로그 줄 인용)
- 원인:
- 해결:
- 재발 방지: (코드/문서/CI에 무엇을 남겼나)
```

---

## T-1. 첫 서명 빌드가 기존 디버그 설치 위에 올라가지 않음
- 발생: 예상되는 문제 (설치 단계)
- 증상: "앱이 설치되지 않았습니다 — 서명이 다른 패키지"
- 원인: 디버그 키와 릴리스 키가 다름. Android는 서명이 다른 같은 패키지명 설치를 거부.
- 해결: 기존 앱 삭제 후 설치. 이후 릴리스 키끼리는 덮어쓰기 가능.
- 재발 방지: 디버그 빌드는 `applicationIdSuffix=".debug"`로 패키지명을 분리해 두 빌드가 공존하도록 함.

## T-3. 로컬 릴리스 빌드가 "No release keystore configured"로 디버그 키 서명됨
- 발생: 2026-10-16, Windows, 프로젝트 경로에 한글(`C:\Project\개인용\…`)
- 증상: `keystore.properties`가 있는데도 `[PocketRisuApp] No release keystore configured - release builds will be signed with the DEBUG key.`
- 원인: `java.util.Properties.load(InputStream)`은 ISO-8859-1로 읽어 `storeFile`의 한글 경로가 깨져 파일이 "없다"고 판정.
- 해결: `app/build.gradle.kts`에서 `f.reader(Charsets.UTF_8)`로 읽도록 변경.
- 재발 방지: 코드 주석 + 이 항목. 경로에 비ASCII가 있는 환경은 CI(ubuntu, ASCII 경로)에서는 재현되지 않으므로 로컬에서만 주의.

## T-2. 서버 주소가 http:// 이면 접속 거부
- 발생: 설계상 의도된 동작
- 증상: 설정 화면에서 "HTTPS 주소만 허용" 오류
- 원인: `network_security_config`로 평문 트래픽을 전부 막음. 세션 쿠키·비밀번호가 평문으로 나가는 것을 방지.
- 해결: Synology 역방향 프록시의 https 주소 사용. 로컬 테스트가 꼭 필요하면 디버그 빌드만 `127.0.0.1` 예외.
- 재발 방지: 문서화(이 항목) + 설정 화면 안내문.
