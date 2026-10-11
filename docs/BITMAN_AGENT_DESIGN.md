# BitMan AI 에이전트 디자인

2026-10-11 사용자 요청: 기존 검정·노랑 브랜드에 AI 에이전트 캐릭터를 추가하고 메인 화면의 정보 밀도를 개선한다. 승인된 캐릭터의 가슴 로고는 `B` 대신 `BitMan`으로 표시한다.

## 화면 구성

- 배경 #101419, 카드 #161b21, 브랜드 골드 #f3cf45, 본문 #f6f3e9, 보조 글자 #a4adba.
- 기존 Noto Sans KR를 유지하고, 제목·숫자·상태의 크기와 굵기를 구분한다.
- 메인 화면: 캐릭터와 검색창 → 조건 바로가기 → 실제 성과 요약 → 조건별 종목 카드. PC는 두 열, 모바일과 태블릿은 한 열.
- 공개 소개 화면에도 같은 캐릭터를 사용한다.
- 데이터, 구독 마스킹, 분석·갱신·히스토리 동작을 유지한다. 캐릭터 영역에 가상의 실시간 상태나 수익률을 추가하지 않는다.

## 캐릭터 자산

`frontend/public/images/bitman-agent-v1.png` — 투명 배경 PNG. 내장 imagegen 도구로 생성 후 프로젝트에 복사했다.

생성 프롬프트:

> Create one premium 3D illustrated AI stock-analysis companion mascot for the Korean app BitMan. A friendly compact robot with a rounded-square charcoal ceramic head, soft metallic golden-yellow armor (#f3cf45), dark glass face with understated warm white/cyan eyes, tiny antenna, and a simple capital B engraved on its chest. Three-quarter view, entire robot visible including feet, one small hand raised in greeting, other hand holding a thin dark tablet. Refined tactile product-render style, calm helpful analyst, centered square cutout, transparent alpha background. No surrounding scenery, pedestal, background chart, coins, money, watermark, purple, or busy neon glow.

사용자 피드백을 반영한 최종 수정 프롬프트:

> Edit only the chest logo of this exact approved BitMan robot mascot. Replace the single capital B engraved on the center gold chest plate with the precise brand wordmark 'BitMan' (capital B and M, other letters lowercase), as a small horizontally centered, highly legible dark charcoal sans-serif wordmark with subtle engraved depth on the same golden chest plate. Fit the full word in one line naturally without stretching or crowding. Preserve everything else exactly: identical robot silhouette, gold and charcoal materials, face expression, antenna, waving hand, tablet, pose, feet, lighting, image framing, resolution, and genuinely transparent background. Do not add other text, objects, background, or change the robot design.

## 검증 및 배포

- 프론트엔드 테스트 30개 통과, TypeScript/Vite 빌드 성공.
- 백엔드 41개 테스트 스위트, 244개 테스트 통과(실행: 2026-10-11 13:11 KST).
- 로컬 브라우저: 320·390·820·821·900·901·1440px 요청 너비에서 수평 넘침 없음. 구독 마스킹, 빈 결과, 검색 분석/닫기, 후보 갱신, 바로가기/활성 메뉴를 확인했다. 로컬 샘플만 사용했으며 실제 LLM은 호출하지 않았다.
- Cloudflare Pages main/production과 MiniPC Spring 정적 리소스를 같은 빌드로 배포했다. JS `index-CyUOO5Sy.js`, CSS `index-CWOuGEdW.css`, SW `bitman-v20261011-agent-design-r1`.
- 실서비스 브라우저에서 메인 화면의 실제 종목 행, 캐릭터 이미지, PC 두 열 카드, 공개 소개 화면과 회원가입 이동을 확인했다.
- MiniPC 로컬 및 공개 API health: `status=ok`. 운영 DB는 배포 전 백업했다.

## 캐릭터 모션 (2026-10-11)

사용자 요청에 따라 승인된 PNG를 그대로 사용하는 `AgentMascot` 공통 컴포넌트를 메인·공개 소개 화면에 적용했다.

- 3.2초 주기로 최대 12px 떠오르며 ±2도 기울어지는 동작을 재생한다.
- PNG 로딩이 완료된 첫 표시, 마우스 진입, 터치·클릭, Enter·Space 입력 시 1.6초 동안 몸 전체가 ±7도 기울며 인사한다. 부유와 인사의 변환 레이어를 분리했다.
- 이미지를 읽은 즉시 자동으로 움직인다. 캐릭터 오른쪽 위의 작은 아이콘으로 원할 때 움직임을 멈추거나 다시 이어서 재생한다. 처음 재생 버튼을 누를 필요는 없다.
- OS의 `prefers-reduced-motion` 설정이 켜져 있으면 작은 부유와 작은 인사로 자동 재생한다. 실행 중 설정이 바뀌어도 자동 재생과 사용자가 선택한 정지 상태는 유지하고 움직임 크기만 조정한다. 기기 설정·스토리지는 변경하지 않는다. 미디어 쿼리 이벤트는 컴포넌트 해제 시 정리한다.
- CSS transform만 사용하며 추가 이미지 생성·모델 호출·타이머·라이브러리 의존성은 없다. 가슴의 `BitMan` 로고와 기존 분석·구독 동작을 유지한다.
- PWA 캐시 버전: `bitman-v20261011-agent-motion-r4`.
- 로컬 브라우저 검증: Home 320·390·820·1440px, Landing 390·1440px에서 수평 넘침 없음. 실제 transform 변화, 정지 후 동일 transform 유지, Enter·Space·클릭 인사 및 종료, 모션 감소의 초기·동적 전환을 확인했다. 320px에서 모션 버튼이 잘리지 않도록 캐릭터 그리드 트랙과 버튼 위치를 조정했다.
- 프론트엔드 테스트 30개 통과, TypeScript/Vite 빌드 성공. 최종 JS `index-dyVAPefE.js`, CSS `index-DhOeHq3L.css`.
- Cloudflare Pages main·production과 MiniPC에 같은 빌드를 배포했다. 두 Pages URL의 JS/CSS 해시와 SW 캐시 버전을 확인했고, 실제 공개 페이지·메인 화면에서 움직임과 인사·정지·재생을 확인했다.
- MiniPC 배포 과정에서 백엔드 244개 테스트가 다시 실행되어 통과했다(2026-10-11 13:22 KST). 운영 DB 백업 후 재시작했으며 MiniPC 로컬·공개 API 모두 `status=ok`, Spring 정적 페이지도 같은 자산 해시를 제공한다.

### 홈에서 움직임이 보이지 않는 문제 수정 (13:46 KST)

- 확인된 조건부 결함: 모션 감소 설정에서는 재생 버튼 자체가 비활성화되고, 초기 인사가 이미지 로딩 전에 끝날 수 있었다. 기존 5초·6px 부유 효과도 모바일에서 식별하기 어려웠다. 사용자의 기기 설정이 원인이라고 단정하지 않는다.
- 회귀 테스트 6개 추가. 기존 코드에서 4개 실패 후 수정본에서 6개 모두 통과했고, 전체 프론트엔드 테스트 36개 및 빌드가 통과했다.
- 실제 로컬 Home 320·390px에서 수동 재생·정지·키보드 인사·동적 모션 설정을 확인했다. 각 120개 화면 샘플로 인사와 부유의 전체 주기 동안 수평 넘침이 없음을 확인했다. Home·Landing PC 1440px도 정상이다.
- 수정본 JS `index-BxZNavDC.js`, CSS `index-Dcx4dBH3.css`.
- Cloudflare Pages main·production 및 MiniPC 배포 완료. 두 Pages URL의 자산 해시·SW r3를 확인했고, 실제 공개 앱에서 3.2초 움직임과 재생·정지 버튼을 확인했다. 실서비스 화면을 4.1초 촬영한 GIF는 `tmp/bitman-agent-motion-fixed.gif`에 보관한다.
- MiniPC 배포 중 백엔드 244개 테스트가 다시 통과했다(13:48 KST). 운영 DB 백업을 보존했고, MiniPC 로컬·공개 health는 `status=ok`, Spring 페이지 자산 해시는 동일하다.

### 재생 조작 없이 자동 시작 (14:13 KST)

- 사용자 요청에 따라 초기 재생 조작을 제거했다. 이미지를 읽으면 자동으로 부유·인사를 시작하며, 보이는 제어는 32px 아이콘(44px 터치 영역)으로 축소했다.
- 모션 감소가 켜져 있어도 4.8초·3px·±0.3도 부유와 ±1도 작은 인사를 자동 재생한다. 설정 전환은 크기만 바꾸고 사용자의 정지 선택을 유지한다.
- 변경된 계약의 테스트 2개가 기존 구현에서 실패한 뒤 수정본에서 전체 36개가 통과했다. TypeScript/Vite 빌드 성공: JS `index-CVOv63WQ.js`, CSS `index-OQVDe-YQ.css`.
- 로컬 실제 Home 컴포넌트: 모션 감소 초기 자동 시작, 동적 설정 전환과 정지 유지, 일반 모션 시작을 확인했다. 320px Home에서 아무 버튼도 누르지 않고 4.13초·160개 화면 샘플의 transform 변화와 수평 넘침 없음, 버튼 오른쪽 299.8px를 확인했다. Landing 390px도 자동 재생과 작은 인사 종료가 정상이다.
- 검증용 HTML/TSX 및 숨김 Vite 서버는 정리했다. 운영 인증·기기 설정·저장소 및 캐릭터 PNG는 변경하지 않았다.
- Cloudflare Pages main/production 및 MiniPC 배포 완료(14:14 KST). 두 Pages의 JS/CSS 해시와 SW r4, Spring 정적 페이지의 동일 해시를 확인했다.
- 실서비스 IAB와 사용 중인 Chrome 탭에서 `data-motion=running`, 32px 아이콘, 빈 버튼 텍스트와 새 JS 해시를 확인했다. 버튼 조작 없이 3.63초 촬영한 실서비스 화면에서 19개 transform 샘플이 모두 달랐다. GIF: `tmp/bitman-mascot-autoplay.gif`.
- MiniPC 배포 중 백엔드 244개 테스트를 다시 실행해 통과했다(14:13 KST). DB 백업·운영 설정을 보존했으며 MiniPC 로컬 및 공개 API health는 `status=ok`이다.