# 번들 프론트 패치

`scripts/build-frontend.sh`가 `upstream.lock`의 PocketRisu 태그를 체크아웃한 뒤, 이 폴더의 `*.patch`를 **파일명 순서**로 `git apply`한다. 패치가 0개여도 번들 모드는 성립해야 한다(순수 upstream 빌드).

규칙 (docs/DESIGN.md §4.2):
- 브랜치 분기 금지. 변경은 전부 여기 패치 파일로.
- 한 패치 = 한 목적. 이름: `NNN-<slug>.patch` (예: `010-lazy-inlay-images.patch`).
- 만드는 법: upstream 체크아웃에서 수정 → `git diff > patches/NNN-slug.patch`.
- 저장 포맷·동기화·인증 코드(`src/ts/storage/*`, `server/`)는 **건드리지 않는다**. 렌더링·UI만.
- 새 upstream 태그에서 적용이 실패하면 `upstream-sync.yml`이 이슈를 만든다 → 리베이스 후 다시 생성.
