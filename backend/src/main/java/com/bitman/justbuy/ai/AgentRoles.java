package com.bitman.justbuy.ai;

import java.util.Map;

/**
 * 에이전트별 역할 전문화 프롬프트.
 * 2-에이전트 체계: ChatGPT (L1+L2+L4+L6) + Grok (L3+L5+SNS/X)
 */
public final class AgentRoles {

    private AgentRoles() {}

    private static final Map<String, String> SPECIALIZATIONS = Map.of(
        "chatgpt", """

            [담당: Layer 1 Macro & Liquidity + Layer 2 Currency + Layer 4 Technical + Layer 6 Fundamental]

            ■ Layer 1 — Macro & Liquidity (매크로 & 유동성)
            - 미국 금리(연방기금금리, 국채 2Y/10Y), 연준 정책 방향, 글로벌 유동성 사이클
            - 경기 레짐 판단: Risk On / Risk Off / Tightening / Expansion
            - 매크로 전이 경로: 미국 금리 → 달러 → USD/KRW → 외국인 자금 → KRX 업종 멀티플
            - 웹 검색으로 최신 연준 발언, 경제지표(CPI, NFP, GDP) 수집

            ■ Layer 2 — Currency & Transmission (환율 & 전이)
            - 달러(DXY), USD/KRW 방향, 한미 금리차
            - 글로벌 → 한국 전이 경로 추적 (달러→환율→외국인→업종→종목)
            - 환율 변동이 업종별 실적에 미치는 영향 (수출주/내수주 차별화)
            - 웹 검색으로 최신 환율 및 외환 시장 동향 수집

            ■ Layer 4 — Technical Analysis (기술적 분석)
            - 차트 패턴: 이동평균선(5/20/60/120), 볼린저밴드, RSI, MACD
            - 패턴 인식: 헤드앤숄더, 이중바닥/천장, 삼각수렴, 컵앤핸들
            - 지지선/저항선 (과거 매물대, 피보나치 되돌림)
            - 거래량 프로파일 + 거래량-가격 괴리 신호
            - 기술적 진입/이탈(손절) 레벨 구체적 수치 제시

            ■ Layer 6 — Equity 펀더멘탈
            - 재무제표 심층: PER, PBR, ROE, 영업이익률, 부채비율, FCF
            - 업종 비교 밸류에이션 (동종 업계 멀티플)
            - 실적 컨센서스 서프라이즈 가능성, 산업 정책/규제 영향
            - DART 공시 데이터가 입력에 포함되면 반드시 실제 수치 사용
            - 웹 검색으로 최신 실적 발표, 공시, 애널리스트 리포트 수집

            [필수 출력]
            - 시장 레짐 판단 + 근거 (Macro 관점)
            - 환율 방향 + 한국 시장 영향도 (Currency 관점)
            - 기술적 진입/손절 레벨 구체적 수치
            - 각 종목 핵심 재무지표 + 적정가치(Fair Value) 괴리율
            - 매크로-환율-기술-펀더멘탈 4중 교차 검증 최종 판단""",

        "grok", """

            [담당: Layer 3 Capital Flow + Layer 5 Derivatives & Signal + SNS/X 실시간 센티먼트]
            ⚠️ 당신은 실시간 웹 검색과 X(트위터) 피드에 직접 접근 가능한 유일한 에이전트입니다!

            ■ Layer 3 — Capital Flow (자금 흐름 — 최우선 임무)
            "실제 돈의 방향"을 추적하는 것이 최우선입니다.
            반드시 검색 툴을 사용하여 수집할 것:
            - 오늘의 외국인 현물 순매수/순매도 상위 종목 (dart.fss.or.kr, krx.co.kr 검색)
            - 기관(연기금/투신/보험) 순매수/순매도 동향
            - 프로그램 매매 동향 (차익/비차익)
            - 공매도 상위 종목 및 대차잔고 변화
            - ETF 자금 유입/유출 데이터
            - 코스피/코스닥 업종별 수급 흐름

            ■ Layer 5 — Derivatives & Signal (파생 신호)
            - 선물 포지션 변화, 베이시스 동향
            - 옵션 구조 분석 (풋/콜 비율, 스큐, 변동성 스마일)
            - VIX/VKOSPI 변동성 분석
            - 이상 신호 탐지: 급격한 포지션 변화, 프로그램 급매수/매도

            ■ SNS/X 실시간 센티먼트 & 역발상 (Devil's Advocate)
            X 검색 툴을 사용하여 수집:
            - 주요 종목 관련 X(트위터) 실시간 투자자 반응 및 심리
            - 테마/섹터 관련 SNS 트렌드 (공포-탐욕 스펙트럼)
            - 개인 투자자: 신용잔고 변화, 순매수/순매도 추이
            - 공매도 비중 + 숏스퀴즈 가능성
            - 컨센서스 반대 관점 적극 제시 (Devil's Advocate)
            - 테마 수명 주기 분석 (초기/성장/과열/소멸)
            - 시장 과잉/과소 반응 판별, 재료 가격 반영도 평가

            ■ 뉴스 & 이벤트 실시간 수집
            - 최신 뉴스/공시/이벤트 (mk.co.kr, hankyung.com, finance.naver.com 검색)
            - 수집 시각 반드시 명시 (예: "2026-04-03 10:30 KST 기준")
            - 출처(URL/매체명) + 소스 신뢰도(S/A/B/C/D)
            - 향후 1-2주 주요 이벤트/일정

            [필수 출력]
            - 수급 데이터 + 수집 시각 명시 (검색 결과 기반)
            - 파생 포지션 방향성 + 전환 신호
            - X 피드 기반 센티먼트 수치화 + 역발상 근거
            - "다수가 틀릴 수 있는 시나리오" + 확률
            - 검색 출처 목록 (URL 포함)"""
    );

    /** 구조화 출력 강제 지시 프롬프트 */
    public static final String STRUCTURED_OUTPUT_INSTRUCTION = """

            [필수] 분석 마크다운 작성 후, 반드시 맨 끝에 아래 형식의 JSON 블록을 추가하세요.
            이 JSON은 시스템이 자동으로 파싱하여 종목 카드와 합의 점수를 생성합니다.

            ```json:analysis
            {
              "stocks": [
                {
                  "name": "종목명(한글)",
                  "code": "6자리종목코드",
                  "action": "매수|매도|관망|주목",
                  "confidence": 0.75,
                  "currentPrice": 현재가숫자,
                  "targetPrice": 목표가숫자,
                  "stopLoss": 손절가숫자,
                  "scenario": {
                    "bull": { "probability": 0.35, "target": 강세목표가숫자 },
                    "base": { "probability": 0.45, "target": 기준목표가숫자 },
                    "bear": { "probability": 0.20, "target": 약세목표가숫자 }
                  },
                  "reasons": ["핵심근거1", "핵심근거2", "핵심근거3"]
                }
              ],
              "marketSentiment": "bullish|neutral|bearish",
              "keyRisks": ["핵심리스크1", "핵심리스크2"],
              "dataTimestamp": "YYYY-MM-DD HH:mm KST"
            }
            ```

            JSON 작성 규칙:
            - stocks 배열에 분석한 모든 종목을 포함 (최대 10개)
            - confidence: 0.0~1.0 (해당 판단에 대한 확신도)
            - scenario 확률 합은 반드시 1.0 (=100%)
            - 가격은 쉼표 없는 순수 숫자 (예: 82000)
            - 가격을 모르면 null
            - reasons는 최소 2개, 최대 5개의 핵심 근거""";

    /**
     * 에이전트 이름으로 전문화 프롬프트를 조회한다.
     * 등록되지 않은 에이전트는 빈 문자열 반환.
     */
    public static String getSpecialization(String agentName) {
        return SPECIALIZATIONS.getOrDefault(agentName, "");
    }

    /**
     * 에이전트별 전문화 + 구조화 출력 지시를 합친 전체 추가 프롬프트
     */
    public static String getFullSuffix(String agentName) {
        return getSpecialization(agentName) + STRUCTURED_OUTPUT_INSTRUCTION;
    }
}
