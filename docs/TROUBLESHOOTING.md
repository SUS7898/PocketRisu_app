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

## T-4. 내보낸 로그 머리에 `server: <server> (version unknown)`
- 발생: 2026-10-16, S26 Ultra(Android 17), 앱 0.1.0. 본문에는 `I/Setup server reachable version=1.14.0`가 있는데 머리만 unknown.
- 증상: 진단 화면을 연 뒤 화면 회전·분할 등으로 액티비티가 재생성되면 서버 확인이 다시 시작되고, 그 응답 전에 내보내면 머리가 비어 있음.
- 원인: 서버 버전은 액티비티 인스턴스 메모리에만 있었고 재생성 시 초기화됨.
- 해결: 마지막 성공 결과를 설정(`lastServerInfo`)에 저장해 응답 전에는 `[cached]` 표기로 사용. 진단 액티비티에 `configChanges` 추가로 회전·분할 시 재생성 방지.
- 재발 방지: 머리 정보는 "없음"보다 "마지막 값 + 표시"를 원칙으로.

## T-5. 첫 CI·Release 실행이 30초 만에 `./gradlew: Permission denied`로 실패
- 발생: 2026-10-16, 첫 push (run 38852569926, 38852771559)
- 증상: Unit tests 단계에서 exit 126.
- 원인: Windows에서 `cp`로 가져온 `gradlew`가 git 인덱스에 `100644`로 들어감(`core.filemode=false`라 실행 비트를 추적하지 않음).
- 해결: `git update-index --chmod=+x gradlew` 후 커밋. v0.1.0 태그는 릴리스가 나오기 전이라 새 커밋으로 옮김.
- 재발 방지: 저장소에 실행 파일을 추가할 때 `git ls-files -s <file>`로 `100755` 확인.

## T-6. PowerShell에서 `-PappVersionName=0.1.0-dev.3+abc`를 넘기면 versionCode 0
- 발생: 2026-10-16 로컬 빌드
- 증상: `android.defaultConfig.versionCode is set to 0`.
- 원인: PowerShell이 `+`가 포함된 인수를 분리해 Gradle에 빈/깨진 값이 전달됨.
- 해결: `--% -PappVersionName="0.1.0-dev.3+abc1234"`(stop-parsing 토큰)로 그대로 전달. CI(bash)는 영향 없음.
- 재발 방지: `app/build.gradle.kts`가 계산된 버전을 `[PocketRisuApp] version … (code …)`로 항상 출력.

## T-2. 서버 주소가 http:// 이면 접속 거부
- 발생: 설계상 의도된 동작
- 증상: 설정 화면에서 "HTTPS 주소만 허용" 오류
- 원인: `network_security_config`로 평문 트래픽을 전부 막음. 세션 쿠키·비밀번호가 평문으로 나가는 것을 방지.
- 해결: Synology 역방향 프록시의 https 주소 사용. 로컬 테스트가 꼭 필요하면 디버그 빌드만 `127.0.0.1` 예외.
- 재발 방지: 문서화(이 항목) + 설정 화면 안내문.
