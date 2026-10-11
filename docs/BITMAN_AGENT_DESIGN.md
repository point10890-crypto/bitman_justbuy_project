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
