# PocketRisu App

자체 호스팅 [PocketRisu](https://github.com/PocketRisu/PocketRisu) 서버를 위한 Android 전용 앱. 서버의 웹 UI를 그대로 쓰면서, 네이티브 층이 삽화·데이터 캐시, 프리페치, 채팅 내보내기, 자체 업데이트, 진단 로그를 맡는다.

- 설계: [docs/DESIGN.md](docs/DESIGN.md)
- PocketRisu 버전이 바뀔 때: [docs/UPSTREAM-UPDATE.md](docs/UPSTREAM-UPDATE.md)
- 로그·진단 규칙: [docs/LOGGING.md](docs/LOGGING.md)
- 트러블슈팅: [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) · 작업일지: [docs/WORKLOG.md](docs/WORKLOG.md)

## 요구사항

- Android 13+ (Galaxy Tab S7+, S26 Ultra에서 사용)
- PocketRisu 서버 **HTTPS** 주소 (역방향 프록시). 평문 http는 거부한다.

## 빌드

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Windows PowerShell: `.\gradlew.bat :app:assembleDebug`. JDK 17, Android SDK 35.

릴리스(서명) 빌드는 저장소 루트의 `keystore.properties`(git-ignored) 또는 환경변수 `KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD`를 읽는다. 없으면 디버그 키로 서명되며 배포하면 안 된다.

## 릴리스

1. GitHub Secrets 4개 등록 (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`). 키는 저장소 밖 `pocketrisu-app-keystore/`에 있다.
2. `git tag vX.Y.Z && git push origin vX.Y.Z` → `release.yml`이 테스트·취약점 스캔·서명 APK·`SHA256SUMS.txt`를 Release로 발행.
3. 앱 내 업데이트가 `releases/latest`를 읽는다.

서명 인증서 SHA-256 (수동 확인용): `378a0f32f1e77cfb2df86e0c10685e8694b8d0a3bb173271f0946793fc24c6f4`

## 진단 로그

앱 메뉴(화면 우상단 가장자리 손잡이) → 진단 · 로그 → 파일로 저장 / 공유. 비밀번호·토큰·쿠키·채팅 본문은 기록 시점에 마스킹된다.
