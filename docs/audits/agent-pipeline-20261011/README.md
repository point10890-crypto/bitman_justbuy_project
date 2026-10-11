# 2026-10-11 오프라인 점검 증거

기준 커밋 `5b678ca8abc0d8f695d1230ded60eef07660d929`의 현재 소스/클래스를 실행한다. 모든 외부 제공자·KIS·DB는 격리된 fixture 또는 mock이다. `.env`와 운영 DB를 읽지 않는다. 이 디렉터리에는 실제 회원 데이터, API 키, 분석 원문, 원문 운영 오류가 없다.

**이 코드는 결함을 재현하는 감사 증거다. exit 0은 앱이 정상이라는 뜻이 아니다.** 결함 수정 후 관측 assertion이 실패할 수 있으며, 그때 별도 정상 동작 회귀 테스트로 옮긴다. 수정 후 같은 결과가 계속 관측되는지를 확인하기 위한 출발점이다.

| 코드 | 범위 | 보존 출력 |
|---|---|---|
| PipelineAuditProbe.java | 합의 중복, 후보 검증, 대체 정책, 실패 완료, 작업 소유권, health 캐시, 저장 실패 | [pipeline-observations.txt](pipeline-observations.txt) |
| RuntimeDataProbe.java | 퍼센트/목표가, 포착 가격 드리프트, 기간별 가격, 예상치를 성과로 집계 | [data-observations.txt](data-observations.txt) |
| AuditProbe.java | 제공자 응답 계약, 근거 보존, 토큰 기록, 검증기/파서 | [provider-observations.txt](provider-observations.txt) |
| ui-audit-probe.mjs | 실제 HomePage/hook/cache의 메모리 transpile/VM/React SSR, 4개 관측 | [ui-observations.txt](ui-observations.txt) |

저장소 루트에서 실행한다. JDK 21 이상, 기존 Gradle 의존성, 설치된 frontend node_modules가 필요하다.

```powershell
& './docs/audits/agent-pipeline-20261011/run-offline-probes.ps1' -JavaHome '<JDK 경로>'
```

JavaHome 생략 시 PATH의 첫 javac/java를 사용한다. Gradle `--offline`으로 현재 클래스를 빌드한다. 실제 앱/서버는 시작하지 않는다. 임시 폴더에 classpath·컴파일 결과·출력을 보존하며 기존 파일을 삭제하지 않는다. 프로덕션 코드는 바꾸지 않는다.

아래 출력은 저장소에 옮긴 runner의 2026-10-11 재실행 결과다. UI 소스 해시를 함께 보존했다. 제공자 probe의 출력 전체가 결함은 아니다. 정상적으로 거부한 응답, 비활성 메서드, 설정 정책 관측도 포함되므로 [판정 보고서](../../AGENT_PIPELINE_AUDIT_2026_10_11.md)를 기준으로 해석한다.
