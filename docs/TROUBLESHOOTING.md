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

## T-7. 채팅(캐릭터) 첫 진입이 오래 걸리고 한 번은 실패함
- 발생: 2026-10-16 14:44, S26 Ultra, 앱 0.1.0-dev.2(code 10002, 서버 UI 모드), 서버 v1.14.0 docker, "거의 빈 채팅방"
- 증상(로그):
  ```
  14:43:55.245 I/Intercept read key=database/database.bin        ← 부트스트랩 시작
  14:44:08.198 I/Intercept asset requests n=50 assets=51           ← 홈 표시 (부트스트랩 ≈ 13초)
  14:44:08~10   I/Intercept read key=plugin-storage/ …  (6건)
  14:44:16.812 I/Intercept chat-content chaId=1f31be28 idx=1 delta=false   ← 채팅 열기
  14:44:46.812 I/Intercept chat-content chaId=1f31be28 idx=1 delta=false   ← 정확히 +30.000초: 클라이언트 재시도
  14:45:22.750 I/Intercept chat-content chaId=1f31be28 idx=0 delta=false   ← 다른 채팅은 바로 열림
  ```
  두 번째 요청이 정확히 30.000초 뒤 = 클라이언트 `FIRST_BYTE_TIMEOUT_MS = 30_000`(`nodeStorage.ts:19`) 만료 → 1회 재시도. 재시도도 30초면 "실패"로 보임. 즉 **서버가 30초 넘게 첫 바이트를 못 보낸 것**이고 기기 쪽 문제가 아니다(같은 서버의 다른 채팅은 즉시 열림).
- 원인(가설, 서버 로그로 확정 필요): PocketRisu 서버는 Node 단일 스레드라 100MB+ DB의 동기 작업(패치 후 지연 저장 `saveTimers` → 전체 DB 재조립·msgpack 인코딩·SQLite 쓰기, 부트스트랩 시 `loadDbCacheIfMissing({createBackup:true})`의 백업 생성, `encodeRisuSaveLegacy` 100MB)이 돌면 **그동안 모든 요청이 멈춘다**. 부트스트랩 직후 클라이언트의 자동 쓰기(boot housekeeping → `/api/patch`)가 지연 저장 타이머를 걸고, 그 저장이 NAS CPU에서 수십 초 걸리는 동안 chat-content가 대기했을 가능성이 가장 크다. 이전 Chrome에서 "다른 채팅 갔다가 돌아오면 열림"도 같은 현상(저장이 끝난 뒤 재요청)으로 설명된다.
- 확인 방법(사용자): 증상 직후 NAS에서 `docker logs --since 5m risuai-nodeonly` 를 보거나 PocketRisu 설정의 로그 화면에서 14:44:16~14:45:20 사이 `[Save]`·`[Patch]`·`[Backup]`·`[Archive]` 줄과 걸린 시간을 확인. 앱 쪽은 마일스톤 2 계측(첫 바이트 ms)으로 서버 지연을 수치화.
- 조치:
  1. `patches/010-chat-first-byte-timeout-120s.patch` — 번들 모드에서 첫 바이트 대기 30초→120초. 느린 건 그대로지만 **실패로 끝나지 않는다**. 서버 UI 모드에는 적용되지 않음(서버의 JS).
  2. 근본: upstream 이슈 후보 — ① `ensureChatStore()`에 진행 중 가드가 없어 재시도가 디코드를 두 번 돌릴 수 있음, ② 지연 저장이 요청 처리를 막음(워커 스레드/비동기 인코딩 필요). 서버 로그로 확정되면 PocketRisu에 제보.
  3. 사용자 측 완화: 캐릭터 비활성화(archive)로 DB를 줄이면 모든 동기 작업이 비례해서 짧아진다.
- 재발 방지: 이 항목 + UPSTREAM-UPDATE C6에 "첫 바이트 타임아웃 상수 위치" 추가.
- **추가 증거 (15:01, dev.4 번들 모드 + 패치 010)**:
  ```
  14:58:55.422 read key=database/database.bin           ← 부트스트랩
  14:59:11.583 asset requests n=50                       ← 홈 (16초)
  14:59:20.274 read key=database/database.bin           ← ★ 두 번째 전체 읽기 (부트스트랩 아님)
  14:59:23.035 chat-content chaId=1f31be28 idx=0        ← 채팅 열기
  15:01:23.036 chat-content … idx=0                     ← +120.001초: 패치 010대로 120초 대기 후 재시도
  15:01:48.356 W/Shell console[error]: TypeError: Failed to fetch (database.svelte-…js)  ← 재시도도 25초 뒤 연결 끊김
  15:01:50.862 chat-content … idx=1                     ← 다른 채팅은 즉시
  ```
  "캐릭터를 열면 채팅을 전부 읽나?" → 아니다. 채팅 본문은 연 것 하나만 `chat-content` 한 건으로 받는다(로그상 한 건). 느린 건 그 한 건의 서버 응답이다.
  두 번째 `database.bin` 읽기는 PocketRisu 코드상 **전체 쓰기(full write) 직후의 기준선 재확인**(`globalApi.svelte.ts` `[Save] … baseline re-seed`)에서 일어난다 → 부팅 뒤 패치 저장이 거부되어(`[Save] Patch hash mismatch` / `Patch rejected`) **100MB+ DB 전체를 업로드**했고, 서버가 그걸 받아 디코드·재조립·저장하는 동안(단일 스레드) 채팅 요청이 멈췄다는 가설이 가장 유력. 이전 Chrome의 "전송 데이터 100MB"도 다운로드만이 아니라 **매번 업로드**였을 가능성.
  확인용으로 앱이 `POST /api/write key=… len=…`·`/api/patch len=…`과 웹 콘솔의 `[Save] …` 경고를 기록하도록 변경(dev.5). 서버 쪽은 `docker logs`에서 `[Patch] Hash mismatch for database/database.bin` 또는 `[Write]` 줄을 찾으면 확정.
  재시도 후 25초 만의 `Failed to fetch`는 역방향 프록시(Synology nginx) 타임아웃으로 연결이 끊긴 것으로 보임 — 서버 응답이 프록시 한도보다 오래 걸린다는 뜻이라 역시 서버 지연의 증거.
- **dev.5 (15:13~15:15, 쓰기 요청 기록 추가 후)**: 채팅 3개(`1f31be28/1`, `0d840f92/0`, `76fe045e/0`)가 **재시도 없이 정상**으로 열림. `POST write key=database/database.bin`은 **없었음** → 이 세션에서는 전체 업로드 가설이 성립하지 않음. 쓰기는 `POST patch`(여러 번)·`chat-content save delta=true`·`plugin-storage` 뿐 = 정상 패턴. 다만 부트스트랩→홈이 **53초**(이전 13~16초)로 느렸고, 직전(15:02~15:06)에 서버 연결 자체가 4분간 실패(T-9)했으므로 **네트워크 경로(공인 IP/NAT 루프백)** 쪽 가능성이 커짐.
- **플러그인 발견**: `[WygLore Leaf 3.1.0] gzip 마이그레이션 3회 시도 후 자동 skip. 옛 plain payload가 큰 환경(78MB+)…` — WygLore 플러그인이 **78MB+ 옛 데이터**를 plugin-storage에 들고 있어 매 부팅마다 읽고(`read key=plugin-storage/ d2wu…` = `wl.*` 키들) 마이그레이션을 시도한다. 부팅·홈 지연과 서버 부하의 큰 몫일 수 있음. 플러그인 자체 안내: "옮기기·복구 탭 → 백업·정리 → [옛 잔류·대용량·미연결 정리] → [저장 사용량 확인]에서 삭제(PC 권장)" 또는 응급 `localStorage.setItem("wl.disable","1")`.
- 현재 판단: 지연 원인 후보는 ① 네트워크 경로(T-9 참고) ② 서버 단일 스레드 정지(패치/백업/대용량 플러그인 저장소) ③ WygLore 78MB 페이로드. 앱 쪽 계측(M2)으로 ①②를 가르고, ③은 사용자가 정리하면 바로 비교 가능.
- **결정적 단서 (18:09 세션, dev.8 번들 모드)**: 쓰기 기록이 있는 세션 4개를 겹쳐 보면 패턴이 하나다.
  | 세션 | `POST /api/db/flush` 시각 | 첫 채팅 열기 | 결과 |
  |---|---|---|---|
  | 15:13 (dev.5) | 15:15:10 | 15:14:05 / 15:14:20 / 15:14:44 (**flush 전**) | 모두 즉시 |
  | 16:53 (dev.7) | 16:53:27 | 16:53:23 (**flush 전**) | 즉시 |
  | 18:09 (dev.8) | 18:09:38 | 18:10:26 (**flush 후**) | 120초 무응답 → 재시도 |
  | 14:44 (dev.2) | (쓰기 미기록) | 14:44:16 | 30초 무응답 → 재시도 |
  `/api/db/flush`는 클라이언트가 **`visibilitychange: hidden`(앱 전환·화면 꺼짐)과 `pagehide` 때마다** 보내는 요청으로(`globalApi.svelte.ts` `flushImmediate → flushServerDbKeepalive`), 서버는 `flushPendingDb()` = **전체 DB(채팅 포함) 재조립 + msgpack 인코딩 + SQLite 쓰기 + 백업 생성**을 `queueStorageOperation` 안에서 즉시 수행한다. NAS에서 100MB+ DB면 수십 초~분 단위이고, 그동안 다른 요청이 뒤에 선다. 사용자가 다른 앱에 갔다가 돌아와 채팅을 열면 정확히 이 작업 뒤에서 기다리게 된다 — "다른 앱 갔다 오면 느리다/안 열린다"와 일치.
  브라우저에서는 탭이 죽기 전 마지막 저장 수단이라 합리적이지만, 이 앱은 WebView를 살려 두므로 매번 강제할 이유가 없다 — 서버는 패치마다 자체 타이머(`SAVE_INTERVAL = 5000ms`, 마지막 패치 5초 뒤)로 어차피 같은 저장을 수행하므로, 생략해도 저장 자체는 최대 5초 늦어질 뿐이다.
- **조치**: `patches/020-skip-forced-flush-on-hide-in-app.patch` — 번들 모드(`__POCKETRISU_APP_BUNDLE__`)에서는 `flushImmediate`가 패치 저장(`triggerSave`)만 하고 `/api/db/flush`는 보내지 않는다. 서버 UI 모드에는 적용 불가. 효과 확인: dev.9 로그에서 `POST /api/db/flush`가 **사라지고** 첫 채팅 열기 재시도가 없어야 한다.
- upstream 제보 후보: ① 포그라운드 복귀 직후 채팅 요청이 flush 뒤에 서는 문제(flush를 비차단/점진으로) ② `ensureChatStore()` 진행 중 가드 부재.

## T-9. 앱 실행 후 44초 동안 빈 화면, 그 뒤 `mode=server-ui (server version unknown)`
- 발생: 2026-10-16 15:12~15:13, dev.5, S26. 직전 15:02~15:06에는 `GET /api/update-check failed after 271611ms — ConnectException … :443`(서버 4분 넘게 연결 불가).
- 증상: `App start 15:12:24` → `server reachable 15:13:08`(44초) → `mode=server-ui (server version unknown)`. 서버 확인은 결국 성공했는데 모드 판정은 "unknown"으로 끝나고, 그 44초 동안 화면이 비어 있었음.
- 원인: ① 서버 확인이 `OkHttp execute()`(블로킹)라 `withTimeoutOrNull(6초)`가 취소 신호를 보내도 블로킹 호출이 끝날 때까지 돌아오지 못함 → 6초 제한이 무력. ② 기본 클라이언트의 경로 재시도(`retryOnConnectionFailure`)로 연결 실패가 271초까지 늘어짐. ③ 타임아웃이 지난 뒤 성공해도 결과는 버려져 "unknown".
- 해결: `Call.await()`(enqueue + 취소 시 `call.cancel()`) + 전용 `probeClient`(connect 5s / read 8s / call 10s, 재시도 없음). 서버에 못 닿으면 **마지막으로 확인된 서버 버전(7일 이내)으로 번들 모드를 시작**하고, 첫 `/api` 응답이 오면 백그라운드로 버전을 재확인해 다르면 서버 UI로 전환(실패 횟수에 넣지 않음). 서버가 죽어 있으면 서버 UI도 못 뜨므로 번들로 시작하는 쪽이 항상 유리.
- 재발 방지: 네트워크 호출은 전부 `await()`로; 블로킹 `execute()`는 가로채기 층(응답 스트리밍) 외에는 쓰지 않는다.
- 참고: 서버 주소가 **공인 IP(125.x)** 로 해석됨. 집 Wi-Fi에서 공인 IP로 접근하면 공유기의 NAT 루프백을 타는데, 일부 공유기는 이 경로가 불안정해 간헐적으로 연결이 끊기거나 수십 초 멈춘다. 채팅 열기 30~120초 지연의 일부가 서버가 아니라 **이 네트워크 경로**일 가능성이 있다 — Synology DDNS 대신 내부 IP/`*.local`을 쓰는 Wi-Fi 전용 주소를 두거나, Tailscale로 접속해 비교해 볼 것.

## T-10. 자체 업데이트 첫 시도가 `INSTALL_FAILED_VERIFICATION_FAILURE: Install not allowed for file:///data/app/vmdl….tmp`
- 발생: 2026-10-16 15:12, dev.4 → dev.5. T-8처럼 **첫 시도 실패, 두 번째 성공**.
- 원인: One UI의 설치 검증(Auto Blocker 또는 Play 프로텍트)이 첫 세션을 거부. 두 번째 시도는 사용자가 경고를 넘기거나 검증 결과가 캐시되어 통과하는 것으로 보임(기기 정책, 앱에서 제어 불가).
- 해결: 실패 메시지에 VERIFICATION/ABORTED가 있으면 "한 번 더 누르면 보통 통과" 안내 토스트. 재시도 시 같은 크기의 APK가 있으면 재다운로드 없이 검증(SHA-256·서명)만 다시 하고 설치.
- 재발 방지: Samsung 설정 → 보안 → Auto Blocker를 끄면 첫 시도에 통과할 가능성이 큼(사용자 선택).

## T-11. 휴대폰에서 웹 UI 상단이 상태 표시줄 밑에 깔려 터치가 불편함 (하단은 제스처 바 밑)
- 발생: 2026-10-16 15:23, S26 Ultra(세로), PocketRisu 플러그인 화면 스크린샷. 페이지 제목·상단 버튼이 상태 표시줄 바로 밑에 붙고, 목록 마지막 줄이 제스처 바에 가려짐.
- 원인: `targetSdk 35`부터 앱이 강제로 edge-to-edge(시스템 바 아래까지 그리기)로 뜨는데, 셸이 WebView에 시스템 바 인셋을 적용하지 않았고 PocketRisu CSS도 `safe-area-inset`을 쓰지 않음. 같은 이유로 **키보드가 올라오면 채팅 입력창이 가려질 수** 있었음(IME 인셋 미처리).
- 해결: `MainActivity.applySystemInsets()` — 루트에 systemBars + displayCutout(카메라 홀) + ime 인셋만큼 패딩. 패딩 띠는 어두운 창 배경색, 시스템 아이콘은 밝게. 설정·진단·서버 설정 화면은 `fitsSystemWindows`. 앱 메뉴 손잡이는 패딩 안쪽 72dp로.
- 같이 점검한 것(같은 뿌리): 하단 제스처 바 겹침(해결), 키보드 겹침(해결), 가로 모드 카메라 홀(해결), 분할 화면(인셋이 창 기준이라 그대로 적용), 태블릿 S7+(3버튼 내비게이션이어도 같은 코드로 처리).
- 남은 것: 앱 메뉴 손잡이(우측 가장자리 14dp)가 PocketRisu 목록의 우측 `⋮` 버튼과 가까워 오터치 가능성 — 사용해 보고 불편하면 위치를 바꾸거나 길게 눌러 숨기기 추가.

## T-12. 다른 앱을 열거나 홈 화면에 나갔다 오면 처음부터 다시 접속함
- 발생: 2026-10-16 16:50~16:53, S26 Ultra, dev.5→dev.7. 522KB 로그 분석.
- 증상(로그):
  ```
  16:50:55.824 E/Shell renderer gone crashed=false priority=2   ← WebView 렌더러가 '크래시'가 아니라 시스템에 의해 종료됨(백그라운드 메모리 회수)
  16:50:55.847 page finished → 16:50:56.653 read key=database/database.bin   ← 셸이 복구했지만 100MB 부트스트랩부터 다시
  16:51:55.846 I/App start …  / 16:53:04.453 I/App start …                   ← 앱 프로세스 자체도 두 번 재시작
  ```
  `priority=2`(IMPORTANT) 상태에서도 죽었으므로 WebView 우선순위 설정만으로는 부족. 디코드된 100MB+ DB를 든 렌더러는 메모리 회수 1순위이고, One UI는 백그라운드 앱을 공격적으로 정리한다.
- 해결(앱):
  1. `KeepAliveService` — 앱이 열려 있는 동안 조용한 포그라운드 알림을 띄우는 서비스(`specialUse`). 프로세스 중요도가 "포그라운드 서비스"로 올라가 LMK 대상에서 멀어지고, `RENDERER_PRIORITY_IMPORTANT`와 합쳐져 렌더러도 보호된다. 설정 "백그라운드 유지"(기본 on), 알림 권한 요청(거부해도 서비스는 동작).
  2. 설정 "배터리 최적화 예외 허용" 버튼(`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). One UI에서는 추가로 설정 → 배터리 → 백그라운드 사용 제한 → **절전 예외 앱**에 추가하고, 최근 앱 화면에서 앱을 **잠금**하면 더 안전.
  3. 그래도 죽었을 때의 복구: PocketRisu 자체에 **마지막 캐릭터 복원** 기능이 있다(`nodeOnlyRestoreLastChat`, `localStorage 'risu-last-active-character'`, 부트 후 `changeChar`). PocketRisu 설정에서 켜 두면 재접속 뒤 홈이 아니라 마지막 캐릭터로 돌아간다. (앱 패치 불필요.)
- 한계: 메모리가 정말 부족하면 포그라운드 서비스도 죽는다. 근본은 렌더러 메모리 = DB 크기이므로 캐릭터 비활성화로 DB를 줄이는 것이 가장 효과적.
- 재발 방지: `renderer gone crashed=false`가 로그에 남으면 이 항목. `priority` 값과 함께 keep-alive 설정 상태를 진단 머리에 표시.

## T-13. WygLore Leaf 플러그인이 `wl.web.v1` 키를 초당 12회 읽음 (10분간 6,370회)
- 발생: 2026-10-16 15:26~15:35, 서버 UI 모드/번들 모드 공통. 로그의 `I/Intercept read key=plugin-storage/ d2wud2ViLnYx…`(= `wl.web.v1`) 6,370건, 15:35 이후 1~3회/분으로 진정.
- 영향: 매 읽기가 NAS로의 HTTP 왕복 + 서버 SQLite 조회. 10분간 서버·네트워크·기기 모두 점유 → 그 직전(15:22~15:24)의 `asset-manifests` 30초 무응답·연결 타임아웃과 시기적으로 겹친다. T-7의 "서버가 막힘" 원인 후보 중 하나.
- 원인: 플러그인 내부(3.1.0, "gzip 마이그레이션 3회 시도" 경고와 같은 세션). 앱이 막을 수는 없고 막아서도 안 된다(플러그인 데이터 무결성).
- 조치(사용자): WygLore Leaf를 3.2.0으로 올렸으면 재발 여부 확인 → 로그에서 `read key=plugin-storage/ d2wud2ViLnYx` 가 분당 수백 건이면 플러그인을 끄고 비교. 옛 78MB 페이로드 정리는 T-7 참고.
- 앱 측: 진단 화면에 "plugin-storage 읽기 분당 건수" 집계를 추가해 재발을 바로 보이게 함(마일스톤 2 계측에 포함).

## T-14. 한 시간 뒤 앱으로 돌아오자 `[Storage] No response headers within 120000ms` 경고 253개가 같은 밀리초에 쏟아짐
- 발생: 2026-10-16 18:01:14, dev.7(keep-alive 없음). 16:53:38 이후 67분간 로그 공백 → 복귀 순간 `asset-manifests` 요청 253건의 타임아웃 경고가 동시에 기록, 이어 `[ModelJobRecovery] discovery failed TypeError: Failed to fetch`.
- 원인: Android의 **캐시 앱 동결(cached-apps freezer)**. 백그라운드에서 프로세스가 통째로 멈춰 진행 중이던 fetch와 타이머가 함께 정지했다가, 복귀 시 한꺼번에 깨어나 모두 "120초 경과"로 판정됨. 서버 장애가 아니라 동결 아티팩트. 연결은 끊겨 있어 재시도가 다시 나간다(서버에 253건 재요청 = 복귀 직후 부하).
- 해결: dev.8의 keep-alive 포그라운드 서비스 — 포그라운드 서비스를 가진 프로세스는 동결 대상에서 제외된다. dev.8 이후 로그에서 같은 패턴이 사라지는지 확인.
- 참고: 이 세션은 67분 백그라운드 뒤에도 렌더러가 살아 돌아왔다(`renderer gone` 없음). 즉 T-12의 종료는 메모리 압박이 있을 때만 일어난다.

## T-15. 태블릿에서 갑자기 새로고침된 것처럼 처음부터 다시 로드됨 (프로세스는 살아 있음)
- 발생: 2026-10-16 20:06:57, Galaxy Tab S7+ (Android 13), dev.9 번들 모드.
- 증상(로그): `I/App start` 없이 `KeepAlive started` → 프로브 `Job was cancelled` → 0.3초 뒤 다시 `KeepAlive started` → 6초 뒤 모드 결정 → `WebView ready` → `page finished`. 즉 **프로세스는 그대로인데 MainActivity가 0.3초 간격으로 두 번 재생성**됐고, 첫 인스턴스의 코루틴은 취소됨. 재생성마다 WebView를 새로 만들어 페이지를 다시 로드했으므로 사용자에겐 "새로고침"으로 보임.
- 원인: 선언하지 않은 **구성 변경**(configuration change). 태블릿은 키보드 커버 연결/분리(`keyboard`·`navigation`), DeX·폰트 크기(`fontScale`), 로캘 등이 자주 바뀌고, 이전 `configChanges`에는 `orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden|uiMode|density`만 있었다. 두 번 연속 재생성은 키보드 커버가 `keyboard`와 `navigation`을 연달아 바꾼 패턴과 일치.
- 해결(두 겹):
  1. `configChanges`에 `keyboard|navigation|fontScale|locale|layoutDirection|colorMode|touchscreen|mcc|mnc|grammaticalGender` 추가 → 대부분의 변경은 재생성 자체가 없음.
  2. 그래도 재생성되는 경우를 위해 **WebView를 Activity 밖(`RetainedSession`)에 보관**: `MutableContextWrapper`로 만든 WebView를 `onDestroy(isFinishing=false)`에서 떼어 두고, 새 Activity가 `baseContext`·클라이언트 host만 바꿔 다시 붙인다. 페이지는 프로세스당 한 번만 로드된다. 로그에 `WebView re-attached after activity recreation`이 남는다.
- 함께 확인된 것: S7+에서 부트스트랩→홈 14초(S26과 동급), 채팅 열기 1회 요청으로 즉시, **`/api/db/flush` 0건**(패치 020 작동), 알림 권한 허용·keep-alive 기동 정상.
- 재발 방지: `I/App start` 없이 `WebView ready`가 다시 찍히면 이 항목. 재생성 원인을 특정하려면 `onConfigurationChanged`에서 바뀐 필드를 로그로 남기는 것이 다음 단계(현재는 생략).

## T-8. 자체 업데이트가 `INSTALL_FAILED_ABORTED: Self update is blocked by unknown source package`로 실패
- 발생: 2026-10-16 14:58, S26 Ultra(One UI, Android 17), dev.2 → dev.4 첫 시도. 두 번째 시도는 성공.
- 원인: `SessionParams.setRequireUserAction(USER_ACTION_NOT_REQUIRED)`를 요청하면 One UI가 "알 수 없는 출처" 패키지의 자체 업데이트를 세션 단계에서 거부함(사용자 확인 창이 뜬 뒤 0.8초 만에 ABORTED).
- 해결: 플래그 제거 — 항상 시스템 확인 창을 거친다(탭 한 번). `UpdateInstaller.install`.
- 재발 방지: 설치 실패는 `E/Update install failed status=… message=…`로 남으므로 로그에서 바로 보인다.

## T-2. 서버 주소가 http:// 이면 접속 거부
- 발생: 설계상 의도된 동작
- 증상: 설정 화면에서 "HTTPS 주소만 허용" 오류
- 원인: `network_security_config`로 평문 트래픽을 전부 막음. 세션 쿠키·비밀번호가 평문으로 나가는 것을 방지.
- 해결: Synology 역방향 프록시의 https 주소 사용. 로컬 테스트가 꼭 필요하면 디버그 빌드만 `127.0.0.1` 예외.
- 재발 방지: 문서화(이 항목) + 설정 화면 안내문.
