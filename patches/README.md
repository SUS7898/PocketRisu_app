# 번들 프론트 패치

`scripts/build-frontend.sh`가 `upstream.lock`의 PocketRisu 태그를 체크아웃한 뒤, 이 폴더의 `*.patch`를 **파일명 순서**로 `git apply`한다. 패치가 0개여도 번들 모드는 성립해야 한다(순수 upstream 빌드).

규칙 (docs/DESIGN.md §4.2):
- 브랜치 분기 금지. 변경은 전부 여기 패치 파일로.
- 한 패치 = 한 목적. 이름: `NNN-<slug>.patch` (예: `010-lazy-inlay-images.patch`).
- Windows에서는 upstream 클론 안에서 `git config core.autocrlf false`부터 (아니면 `reset --hard`가 파일을 CRLF로 되돌려 패치가 전부 어긋난다 — docs/TROUBLESHOOTING.md T-19).
- 만드는 법: upstream 체크아웃에서 **먼저 `git reset --hard`**(빌드 스크립트의 `git apply --3way`가 이전 패치를 인덱스에 올려 두므로 `checkout -- file`로는 원복되지 않음) → 수정 → `git diff > patches/NNN-slug.patch` → `git reset --hard` → `git apply --check patches/NNN-slug.patch`로 확인. Windows에서는 autocrlf로 파일이 CRLF가 되니 편집 도구가 줄바꿈을 보존하는지 확인(파이썬이면 `newline='\r\n'`).
- 저장 포맷·동기화·인증 코드(`src/ts/storage/*`, `server/`)는 **건드리지 않는다**. 렌더링·UI만.
- 새 upstream 태그에서 적용이 실패하면 `upstream-sync.yml`이 이슈를 만든다 → 리베이스 후 다시 생성.
