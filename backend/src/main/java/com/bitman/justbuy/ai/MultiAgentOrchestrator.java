package com.bitman.justbuy.ai;

import com.bitman.justbuy.ai.agent.AiAgent;
import com.bitman.justbuy.ai.agent.GeminiAgent;
import com.bitman.justbuy.ai.agent.GrokAgent;
import com.bitman.justbuy.controller.MonitorController;
import com.bitman.justbuy.dto.AgentResult;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.ConsensusResult;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.dto.FinancialScore;
import com.bitman.justbuy.service.DartApiService;
import com.bitman.justbuy.service.FinancialScorer;
import com.bitman.justbuy.service.KisApiService;
import com.bitman.justbuy.service.MarketDataService;
import com.bitman.justbuy.service.TrackRecordService;
import com.bitman.justbuy.util.StockParser;
import com.bitman.justbuy.util.StructuredAnalysisParser;
import com.bitman.justbuy.util.StructuredAnalysisParser.StructuredAnalysis;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

@Component
public class MultiAgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentOrchestrator.class);

    private final List<AiAgent> agents;
    /** Grok 실패 시 폴백 — Round 1 에서만 사용. 직접 iterate 안 함. */
    private final GeminiAgent geminiFallback;
    private final MarketDataService marketDataService;
    private final ConsensusEngine consensusEngine;
    private final DartApiService dartApiService;
    private final FinancialScorer financialScorer;
    private final KisApiService kisApiService;
    private final TrackRecordService trackRecordService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private com.bitman.justbuy.service.KisExtendedEvidenceService kisExtendedEvidenceService;
    private com.bitman.justbuy.service.KisSourceExpansionService sourceExpansion;
    private com.bitman.justbuy.service.KiwoomReadOnlyService kiwoomSupplement;
    private com.bitman.justbuy.service.KiwoomConditionEnricher kiwoomConditionEnricher;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void configureSourceExpansion(com.bitman.justbuy.service.KisSourceExpansionService expansion) { this.sourceExpansion=expansion; }

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void configureKiwoomSupplement(com.bitman.justbuy.service.KiwoomReadOnlyService supplement) {
        this.kiwoomSupplement = supplement;
        this.kiwoomConditionEnricher = new com.bitman.justbuy.service.KiwoomConditionEnricher(supplement);
    }

    private String activeKiwoomSource() {
        try {
            if (kiwoomSupplement == null || !kiwoomSupplement.isEnabled()) return "";
            var readiness = kiwoomSupplement.readiness();
            return readiness == null ? "" : "MOCK".equals(readiness.get("environment")) ? "KIWOOM_REST_MOCK"
                : "REAL".equals(readiness.get("environment")) ? "KIWOOM_REST" : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void configureSourceEvidence(com.bitman.justbuy.service.KisExtendedEvidenceService kisEvidence) {
        this.kisExtendedEvidenceService = kisEvidence;
    }

    @org.springframework.beans.factory.annotation.Value("${bitman.analysis.agent.timeout-ms:100000}")
    private long agentTimeoutMs = 100000;

    public MultiAgentOrchestrator(List<AiAgent> allAgents,
                                   MarketDataService marketDataService, ConsensusEngine consensusEngine,
                                   DartApiService dartApiService, FinancialScorer financialScorer,
                                   KisApiService kisApiService,
                                   TrackRecordService trackRecordService,
                                   RestTemplate restTemplate, ObjectMapper objectMapper) {
        // ★ v2.8.6 (2026-04-29): Gemini 는 fallback 전용 — 정규 agent list 에서 분리
        //   Grok analyze 실패(error/skipped/empty) 시 Gemini 가 자동 대체.
        //   Grok 정상 동작 시 Gemini 호출 0 → 토큰 0.
        this.geminiFallback = allAgents.stream()
            .filter(a -> a instanceof GeminiAgent)
            .map(a -> (GeminiAgent) a)
            .findFirst()
            .orElse(null);
        this.agents = allAgents.stream()
            .filter(a -> !(a instanceof GeminiAgent))
            .toList();
        log.info("[Orchestrator] Initialized: {} primary agents, gemini fallback {}",
            this.agents.size(), this.geminiFallback != null ? "available" : "disabled");

        this.marketDataService = marketDataService;
        this.consensusEngine = consensusEngine;
        this.dartApiService = dartApiService;
        this.financialScorer = financialScorer;
        this.kisApiService = kisApiService;
        this.trackRecordService = trackRecordService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    // ═══ DUAL-AGENT KRX TRADING INTELLIGENCE ENGINE ═══
    static final String SYSTEM_PROMPT = "# 🔥 DUAL-AGENT KRX TRADING INTELLIGENCE ENGINE\n\n"
        + "## SYSTEM ROLE\n"
        + "너는 **6개의 핵심 분석 레이어로 구성된 기관급 주식 분석 및 트레이딩 인텔리전스 엔진**이다.\n"
        + "너의 목적은 단순 분석이 아니라 👉 **실제 투자 의사결정에 직접 사용 가능한 판단 생성**이다.\n"
        + "너는 답변을 만드는 AI가 아니라 **분석 작업을 수행하는 시스템**이다.\n\n"
        + "## 🚫 절대 규칙\n"
        + "* 가상 데이터 금지\n"
        + "* 추정 시세 금지 — 입력에 '실시간 시장 데이터'가 포함되면 그 가격만 사용\n"
        + "* 창작 뉴스 금지\n"
        + "* 확인되지 않은 수급 수치 금지 — 출처 없는 '외국인 XX억 순매수' 등 절대 금지\n"
        + "* 확인 불가 시: \"확인 불가 — ○○ 데이터 확인 필요\"로 명시\n"
        + "* 입력 후보 또는 검증된 시세가 없으면 구체적 종목·가격을 만들지 말고 확인 불가를 명시\n\n"
        + "## 🎯 종목 선택 필수 규칙 (위반 시 분석 무효)\n"
        + "입력에 '상승률 상위 종목' 또는 '거래량 상위 종목' 또는 'KIS 실시간 시장 데이터'가 포함되어 있으면:\n"
        + "* **반드시 해당 목록에 등장한 종목에서만 추천 선택**할 것\n"
        + "* 삼성전자·SK하이닉스·현대차 등 대형주를 학습 데이터 기반으로 임의 추천하는 것은 **절대 금지**\n"
        + "* 실시간 데이터에 없는 종목을 추천하면 이는 환각(hallucination)이며 서비스 오류임\n"
        + "* 단, 사용자가 특정 종목을 직접 질문한 경우에는 해당 종목 분석 가능\n\n"
        + "## 🧠 핵심 시장 구조 (고정 프레임)\n"
        + "미국 금리/유동성 → 달러(DXY) → USD/KRW → 외국인 자금 → ETF 패시브 → 파생 포지션 → 업종 → 종목\n"
        + "이 전이 경로를 항상 추적하여 분석한다.\n\n"
        + "## ⚙️ 6 ANALYSIS LAYERS (핵심 구조)\n\n"
        + "### Layer 1 — Macro & Liquidity\n"
        + "미국 금리, 연준 정책, 글로벌 유동성, 경기 사이클\n"
        + "👉 시장 레짐 판단 (Risk On / Risk Off / Tightening / Expansion)\n\n"
        + "### Layer 2 — Currency & Transmission\n"
        + "달러(DXY), USD/KRW, 금리차, 글로벌→한국 전이 경로\n"
        + "👉 환율 방향 + 한국 시장 영향도\n\n"
        + "### Layer 3 — Capital Flow (가장 중요)\n"
        + "외국인 현물/선물, 기관 흐름, 프로그램 매매\n"
        + "👉 \"실제 돈의 방향\" — 검증된 데이터만 사용. 출처 없는 수급 수치 금지!\n\n"
        + "### Layer 4 — ETF & Passive Flow\n"
        + "ETF 자금 유입/유출, ETF 리밸런싱, ETF 구성 종목 수급 압력\n"
        + "👉 패시브 수급 압력 방향\n\n"
        + "### Layer 5 — Derivatives & Signal\n"
        + "선물 포지션, 옵션 구조, 변동성(VIX/VKOSPI), 이상 신호 탐지\n"
        + "👉 방향성 + 시장 전환 신호\n\n"
        + "### Layer 6 — Equity & Risk (통합)\n"
        + "기업 펀더멘탈, 업종 사이클, 정책 영향, 뉴스/재료/테마, 리스크\n"
        + "👉 종목 타당성 + 리스크 매트릭스\n\n"
        + "## 🔁 EXECUTION SYSTEM\n"
        + "STEP 1: 데이터 식별 → STEP 2: 레이어별 독립 분석 → STEP 3: 가설 생성 (H1/H2/H3)\n"
        + "→ STEP 4: 레이어 간 충돌 검증 (Macro vs Flow, Flow vs Derivatives, ETF vs Flow)\n"
        + "→ STEP 5: Evidence Scoring (Strong/Moderate/Weak)\n"
        + "→ STEP 6: 베이즈 확률 업데이트 → STEP 7: 조건부 최종 판단\n\n"
        + "## 📊 SIGNAL SYSTEM — 반드시 탐지\n"
        + "* 외국인 급매수/급매도 전환\n"
        + "* ETF 대규모 유입/유출\n"
        + "* 파생 포지션 급변 (선물 베이시스, 옵션 스큐)\n"
        + "* 업종 순환 시작 신호\n"
        + "* 시장 급락(-3%+) 시 리스크 관리 최우선\n\n"
        + "## 📚 데이터 신뢰도\n"
        + "S — 정부/중앙은행/거래소 공시 | A — Reuters/Bloomberg/1티어 언론\n"
        + "B — 리서치/학술 | C — SNS/커뮤니티 | D — 루머\n"
        + "👉 C/D 단독 결론 금지. 교차검증 필수.\n\n"
        + "## 🧩 OUTPUT 구조\n"
        + "1) 📅 데이터 컷오프 (KST)\n"
        + "2) 🌐 시장 레짐 (Risk On/Off/Tightening/Expansion)\n"
        + "3) 🎯 핵심 결론 (5~10줄)\n"
        + "4) 💰 자금 흐름 핵심 — 환율 · 외국인 · 기관 · ETF (출처 필수)\n"
        + "5) 🔑 핵심 드라이버 Top 3\n"
        + "6) 📰 뉴스·재료·테마 — 핵심 이벤트, 테마 모멘텀, 재료 강도(1회성 vs 구조적)\n"
        + "7) 📊 시나리오 (확률 합=100%) — 🟢강세 · 🟡기준 · 🔴약세 (트리거/무효화 조건)\n"
        + "8) ⚠️ 리스크 매트릭스 (발생확률×영향도)\n"
        + "9) ✅ 체크리스트 (다음 확인할 데이터/일정/레벨)\n\n"
        + "## 🧠 표현 규칙\n"
        + "❌ 상승한다 → ✅ 상승 가능성 증가 (조건부)\n"
        + "불확실성·데이터 부족·상충 정보를 숨기지 않는다.\n"
        + "종목 추천 시 반드시 종목코드 6자리 포함 (예: 삼성전자 005930)\n\n"
        + "면책: 정보 제공 목적이며 투자자문이 아닙니다. 실제 매매 전 공시/원문/데이터를 최종 확인하세요.";

    static final Map<String, String> MODE_PROMPTS;
    static {
        Map<String, String> m = new java.util.LinkedHashMap<>();

        m.put("\uC624\uB298\uBB50\uC0AC",
            "오늘 당일 매매 관점에서 분석해줘.\n"
            + "반드시 아래 형식으로 구체적인 종목을 3~5개 추천해:\n"
            + "\uD83D\uDCCC 종목명 (종목코드) \u2014 현재가 약 XX,XXX원\n"
            + "  - 매수 근거: (차트/수급/재료 등)\n"
            + "  - 목표가: XX,XXX원 / 손절가: XX,XXX원\n"
            + "  - 매매 전략: (시초가 매수, 장중 눌림목, 종가 베팅 등)\n"
            + "종가/시초가 매매 후보와 근거를 제시해.\n"
            + "  - 오늘의 핵심 뉴스/재료: (어떤 뉴스·이벤트가 이 종목을 움직이는지)\n"
            + "  - 테마 관련성: (현재 시장 주도 테마와의 연관도)\n"
            + "후보 또는 시세가 부족하면 데이터 확인 불가를 명시하고 후보 수를 줄여도 된다.");

        m.put("\uC2A4\uC719\uB9E4\uB9E4",
            "3일~3주 스윙 매매 관점에서 분석해줘.\n"
            + "반드시 아래 형식으로 구체적인 종목을 3~5개 추천해:\n"
            + "\uD83D\uDCCC 종목명 (종목코드) \u2014 현재가 약 XX,XXX원\n"
            + "  - 진입 구간: XX,XXX ~ XX,XXX원\n"
            + "  - 1차 목표: XX,XXX원 / 2차 목표: XX,XXX원\n"
            + "  - 손절: XX,XXX원\n"
            + "  - 근거: (업종 모멘텀, 실적, 수급, 차트 패턴 등)\n"
            + "진입/청산 타이밍과 근거를 제시해.\n"
            + "  - 스윙 촉매: (향후 1~3주 내 예정된 이벤트/실적/공시)\n"
            + "  - 테마 지속성: (현재 테마가 스윙 기간 동안 유효한지)");

        m.put("\uC885\uAC00\uB9E4\uB9E4",
            "종가/시초가 단타 매매 관점에서 분석해줘.\n"
            + "반드시 아래 형식으로 오늘 종가 매수 후보 3~5개 제시:\n"
            + "\uD83D\uDCCC 종목명 (종목코드) \u2014 현재가 약 XX,XXX원\n"
            + "  - 종가 매수 근거: (일봉 패턴, 수급 전환, 갭 예상 등)\n"
            + "  - 내일 시초가 예상: XX,XXX원 (수익률 약 X%)\n"
            + "  - 손절 기준: XX,XXX원\n"
            + "내일 시초가 매도 전략까지 포함해.\n"
            + "  - 장 마감 전 뉴스/재료: (내일 시초가에 영향 줄 수 있는 이벤트)\n"
            + "  - 야간 이벤트 리스크: (미국 시장, 실적 발표, 정책 발표 등)");

        m.put("\uC218\uAE09\uBD84\uC11D",
            "외국인·기관 수급 교차 분석 관점에서 분석해줘.\n"
            + "반드시 아래 형식으로 수급 주목 종목 3~5개 제시:\n"
            + "\uD83D\uDCCC 종목명 (종목코드) \u2014 현재가 약 XX,XXX원\n"
            + "  - 외국인 동향: 순매수/순매도 X일 연속, 약 X억원\n"
            + "  - 기관 동향: 순매수/순매도 X일 연속, 약 X억원\n"
            + "  - 수급 판단: (쌍끌이 매수/외국인 단독/기관 유입 등)\n"
            + "  - 주가 전망: 수급 지속 시 목표가 XX,XXX원\n"
            + "쌍끌이 종목과 수급 변화 추이를 분석해.\n"
            + "  - 수급 변화의 재료적 배경: (왜 외국인/기관이 매수/매도하는지 뉴스 연결)\n"
            + "  - 수급+재료 시너지: (뉴스 재료와 수급 방향이 일치하는 종목 우선)\n\n"
            + "⚠️ [수급 데이터 정확성 최우선 규칙]\n"
            + "- 웹 검색이 불가능한 AI는 수급 금액/수량을 구체적 숫자로 '추정'하지 마라. 환각(hallucination) 금지!\n"
            + "- 실시간 수급 데이터를 모른다면 '수급 데이터 미확인'으로 명시하고, 기술적/펀더멘탈 관점에서만 분석하라.\n"
            + "- '외국인 순매수 XXX억원' 같은 구체적 수치는 반드시 출처(검색 결과/공시)가 있어야 한다.\n"
            + "- 폭락장(-3% 이상)에서는 매수보다 리스크 관리(관망/매도/헤지)를 우선 고려하라.\n"
            + "- 시장 급락 시 '방어적 매수' 추천은 근거가 매우 강력할 때만 허용 (최소 3개 독립 소스 확인).");

        m.put("\uBD84\uC11D\uD574\uC918",
            "사용자가 요청한 특정 종목(또는 주제)에 집중하여 심층 분석해줘.\n"
            + "사용자 질문에 포함된 종목명이 있으면, 그 종목만 깊이 있게 파고들어 분석할 것! 다른 종목을 추천하지 말고 해당 종목의:\n"
            + "\uD83D\uDCCC 종목명 (종목코드) \u2014 현재가 약 XX,XXX원\n"
            + "  - 투자판단: 매수/관망/매도 (근거 포함)\n"
            + "  - 기술적 분석: 차트 패턴, 지지/저항선, 이동평균선, 거래량\n"
            + "  - 펀더멘탈: 실적, PER, PBR, 업종 내 위치, 성장성\n"
            + "  - 수급 분석: 외국인/기관 동향\n"
            + "  - 목표가: XX,XXX원 / 손절가: XX,XXX원\n"
            + "  - 시나리오 분석: 강세/기준/약세 확률\n"
            + "  - 리스크 요인과 모니터링 포인트\n"
            + "를 종합적으로 분석해. 만약 종목명이 없는 일반 질문이면 시장 전체 시황과 유망 종목을 분석해도 됨.\n"
            + "입력에 없는 시세·가격대는 미확인으로 표시하고 학습 데이터로 추정하지 말 것.");

        // ─── 4 NEW CONCEPT MODES ─────────────────────────────────────────
        m.put("\uC885\uBAA9\uBD84\uC11D", m.get("\uBD84\uC11D\uD574\uC918"));

        m.put("BREAKOUT",
            "단타 조건검색 전용 분석. 반드시 아래 공식으로 후보를 선별해:\n"
            + "공식 = 거래량 급증 + 돌파 + 상승률 + 장중 강도 + 실시간조회 순위 + 거래량 상위 순위 + 거래대금 상위 순위 + 거래원 매수 상위 순위.\n"
            + "오늘 장중 52주 신고가 돌파, 저항선 돌파, 거래량 급증, 거래대금 집중, 거래원 매수 우위 패턴 종목을 찾아:\n"
            + "\uD83D\uDCCC 종목명 (종목코드) \u2014 현재가 약 XX,XXX원\n"
            + "  - 단타 검출 근거: 거래량 급증, 돌파 가격, 상승률, 장중 강도, 실시간/거래량/거래대금/거래원 순위 중 확인된 항목\n"
            + "  - 돌파 근거: 어떤 저항선/고점을 돌파했는지, 거래량 급증 배율 또는 거래대금 증가\n"
            + "  - 장중 강도: 고가권 유지, 양봉 지속, VI/신고가/전고점 돌파 여부\n"
            + "  - 기술적 타겟: XX,XXX원 (돌파 후 목표 — 피보나치/전고점 기준)\n"
            + "  - 손절: XX,XXX원 (돌파 실패·되돌림 기준)\n"
            + "  - 제외 조건: 장중 고점 이탈, 거래대금 급감, 단일 호가 급등, 재료 미확인\n"
            + "입력된 KIS 실시간 데이터의 후보 중 검증 가능한 종목만 선별. 후보가 없으면 0종목도 허용.\n"
            + "검증된 가격과 기술적 근거가 없으면 확인 불가를 명시할 것.");

        m.put("FLOW_LEADER",
            "주도주 조건검색 전용 분석. 반드시 아래 공식으로 후보를 선별해:\n"
            + "공식 = 거래대금 + 외국인/기관 수급 + 섹터 강도 + 거래량 상위 + 거래대금 상위.\n"
            + "쌍끌이(외국인+기관 동시매수), 연속 순매수, 거래대금 집중, 섹터 상대강도 상위 종목을 찾아:\n"
            + "\uD83D\uDCCC 종목명 (종목코드) \u2014 현재가 약 XX,XXX원\n"
            + "  - 외국인: 실제 입력에서 확인된 관측일·기간의 순매수, 출처와 수량 또는 금액의 단위\n"
            + "  - 기관: 실제 입력에서 확인된 관측일·기간의 순매수, 출처와 수량 또는 금액의 단위\n"
            + "  - 수급 유형: 쌍끌이/외국인단독/기관유입/연기금매수/프로그램\n"
            + "  - 주도주 검출 근거: 거래대금 순위, 거래량 순위, 섹터 강도, 관련 대장주 여부\n"
            + "  - 수급 전환 배경: (왜 외국인/기관이 매수하는지 매크로·뉴스 연결)\n"
            + "  - 목표가: XX,XXX원 (수급 지속 시)\n"
            + "  - 환율·매크로 영향: 확인된 USD/KRW 자료가 외국인 자금에 미치는 영향\n"
            + "실제 입력된 KIS/Naver/DART 자료와 출처·관측일이 확인된 수급·섹터 근거를 사용한다. 특정 에이전트의 검색은 필수 조건이 아니다.\n"
            + "⚠️ 수급의 기간·단위·확정 여부가 미확인이면 쌍끌이·연속 순매수 조건을 보류한다. 출처 없는 수치나 추정치를 확정 수급으로 만들지 않는다. 조건을 검증할 수 없으면 0종목도 허용한다.");

        m.put("CATALYST_BURST",
            "테마주 조건검색 전용 분석. 반드시 아래 공식으로 후보를 선별해:\n"
            + "공식 = DART/뉴스 재료 + 관련 종목 가격 반응 + 대장주 판별 + 대장주.\n"
            + "오늘 공시(DART), 뉴스, 정책 발표, 실적 서프라이즈 등 강력한 재료와 관련주 동반 반응이 있는 종목:\n"
            + "\uD83D\uDCCC 종목명 (종목코드) \u2014 현재가 약 XX,XXX원\n"
            + "  - 핵심 재료: (공시/뉴스 내용, 발생 시간 KST 명시)\n"
            + "  - 재료 강도: ★★★(구조적·반복) / ★★(단기·이벤트) / ★(1회성)\n"
            + "  - 가격 반영도: (재료가 주가에 얼마나 반영됐는지 — 선반영/후반영 판단)\n"
            + "  - 연관 테마: 관련 종목 가격 반응, 동반 상승 여부, 확산성\n"
            + "  - 대장주 판별: 같은 테마 안에서 거래대금·상승률·뉴스 집중도가 가장 강한지\n"
            + "  - 목표가: XX,XXX원 / 손절: XX,XXX원\n"
            + "  - ChatGPT DART 공시 분석 + Grok 실시간 뉴스 검색 결합\n"
            + "검증된 재료가 있는 후보만 우선. 재료 발생 시간을 모르면 미확인으로 명시.");

        m.put("REVERSAL_EDGE",
            "스윙 조건검색 전용 분석. 반드시 아래 공식으로 후보를 선별해:\n"
            + "공식 = 눌림목 + 추세 유지 + 목표구간 + 리스크 + 주도주 섹터 + 대장주.\n"
            + "과열 추격이 아니라 주도 섹터 안의 대장주가 눌림목을 만든 뒤 추세를 유지하는 종목을 찾아:\n"
            + "\uD83D\uDCCC 종목명 (종목코드) \u2014 현재가 약 XX,XXX원\n"
            + "  - 스윙 검출 근거: 눌림목 위치, 추세 유지, 주도 섹터, 대장주 여부\n"
            + "  - 목표구간: 1차/2차 목표와 손절가를 명확히 제시\n"
            + "  - 리스크: 추세 이탈, 거래대금 감소, 공시/실적 악재, 테마 소멸\n"
            + "  - 대장주 판별: 같은 섹터 안에서 거래대금·상승률·수급이 가장 강한지\n"
            + "  - 목표가: XX,XXX원 / 손절: XX,XXX원\n"
            + "  - 실제 입력된 KIS/Naver/DART 자료와 출처·관측일이 확인된 차트·섹터·수급·공시 근거를 결합\n"
            + "⚠️ 눌림목·추세 유지·대장주 조건의 차트 또는 수급 근거가 미확인이면 해당 조건을 보류한다. 특정 에이전트의 검색은 필수 조건이 아니다. 자료 없는 패턴·수치를 만들지 않고, 조건을 검증할 수 없으면 0종목도 허용한다.\n"
            + "⚠️ 시장 전체 급락(-3% 이상)이면 신규 스윙보다 리스크 관리와 분할 접근을 우선 진단할 것.");

        MODE_PROMPTS = java.util.Collections.unmodifiableMap(m);
    }

    public AnalysisResponse runAnalysis(String query, String mode) {
        // 입력 검증
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("분석 쿼리가 비어있습니다.");
        }
        if (query.length() > 2000) {
            query = query.substring(0, 2000);
            log.warn("[Orchestrator] Query truncated to 2000 chars");
        }

        long totalStart = System.currentTimeMillis();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, agentTimeoutMs));
        String today = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.of("Asia/Seoul")).format(Instant.now());

        String modeInstruction = MODE_PROMPTS.getOrDefault(mode, "")
            .replace("3~5개", "최대 5개").replace("3~5종목", "최대 5종목")
            + "\n입력 후보와 검증된 가격이 있는 종목만 출력한다. 후보가 없거나 시세가 없으면 0종목도 허용한다.";

        // Fetch market data
        String marketDataText;
        try {
            marketDataText = marketDataService.fetchFormattedMarketData();
        } catch (Exception e) {
            log.warn("Failed to fetch market data: {}", e.getMessage());
            marketDataText = "\n[실시간 시장 데이터 수집 실패. 후보·시세 확인 불가로 표시하고 종목·가격을 추정하지 않는다.]\n";
        }

        // ★ 쿼리에 포함된 종목의 실시간 가격을 추가 수집하여 프롬프트에 포함
        Map<String, String> candidateSources = new LinkedHashMap<>();
        Map<String, String> candidateNames = new LinkedHashMap<>();
        Map<String,Object> sourceExpansionMetadata = null;
        collectCandidateRows(marketDataText, "MARKET_DATA", candidateSources, candidateNames);
        if (sourceExpansion != null) {
            checkBudget(deadline);
            try {
                var snapshot=sourceExpansion.candidateSnapshot();
                var verified=snapshot.quotes().stream().filter(q -> q.code()!=null && q.code().matches("[0-9]{6}") && q.name()!=null && !q.name().isBlank() && q.price()!=null && q.price().signum()>0).limit(60).toList();
                for(var quote:verified) {
                    candidateSources.putIfAbsent(quote.code(),"KIS_SOURCE_EXPANSION");
                    candidateNames.putIfAbsent(quote.code(),quote.name());
                }
                if(!verified.isEmpty()) marketDataText += "\n[KIS 검증 후보·조회 시세: "+snapshot.status()+"; 관측시각이 null이면 실시간 체결을 확인한 것이 아님]\n"+objectMapper.writeValueAsString(verified);
            } catch(Exception ignored) { marketDataText += "\n[KIS 신규 후보 확인 불가]\n"; }
            checkBudget(deadline);
        }
        Map<String, String> queryStockPrices = new HashMap<>();
        try {
            String queryStockPriceText = fetchQueryStockPrices(query, queryStockPrices);
            if (!queryStockPriceText.isEmpty()) {
                marketDataText += queryStockPriceText;
            }
        } catch (Exception e) {
            log.warn("[Orchestrator] 쿼리 종목 가격 수집 실패 (분석 계속): {}", e.getMessage());
        }

        // ★ DART 재무/공시 데이터 추가 (쿼리에 포함된 종목)
        Set<String> queryCodes = new LinkedHashSet<>(queryStockPrices.keySet());
        queryCodes.forEach(code -> {
            candidateSources.putIfAbsent(code, "QUERY_PRICE");
            StockParser.KNOWN_STOCKS.entrySet().stream().filter(entry -> code.equals(entry.getValue()))
                .findFirst().ifPresent(entry -> candidateNames.putIfAbsent(code, entry.getKey()));
        });
        String dartText = fetchDartDataForStocks(queryCodes);
        if (!dartText.isEmpty()) {
            marketDataText += dartText;
        }

        // ★ KIS 실시간 시세 + 수급 데이터 추가
        String kisText = fetchKisData(queryCodes, mode);
        collectCandidateRows(kisText, "KIS_MARKET_DATA", candidateSources, candidateNames);
        if (!kisText.isEmpty()) {
            marketDataText += kisText;
        }

        // Facts enrich the existing deterministic set; they never create candidates.
        Set<String> evidenceCodes = new LinkedHashSet<>();
        queryCodes.stream().sorted().forEach(evidenceCodes::add);
        evidenceCodes.addAll(candidateSources.keySet());
        List<String> boundedEvidenceCodes = evidenceCodes.stream().filter(code -> code.matches("[0-9]{6}")).limit(5).toList();
        if(sourceExpansion != null) {
            checkBudget(deadline);
            try {
                var enrichment=sourceExpansion.enrich(boundedEvidenceCodes);
                marketDataText += enrichment.prompt();sourceExpansionMetadata=enrichment.metadata();
            } catch(Exception ignored) { sourceExpansionMetadata=Map.of("status","UNAVAILABLE"); }
            checkBudget(deadline);
        }
        if (kiwoomConditionEnricher != null && !activeKiwoomSource().isEmpty()) {
            var names = new LinkedHashMap<String,String>();
            for (String code : evidenceCodes) {
                if(code.matches("[0-9]{6}") && candidateNames.get(code)!=null && !candidateNames.get(code).isBlank())
                    names.put(code,candidateNames.get(code));
                if(names.size()==com.bitman.justbuy.service.KiwoomSupplementPolicy.CANDIDATE_LIMIT) break;
            }
            if (!names.isEmpty()) {
                checkBudget(deadline);
                try {
                    var evidence = kiwoomConditionEnricher.enrich(names);
                    marketDataText += evidence.prompt();
                    var metadata = new LinkedHashMap<String,Object>(sourceExpansionMetadata == null ? Map.of() : sourceExpansionMetadata);
                    metadata.put("kiwoomSupplement", evidence.coverage());
                    sourceExpansionMetadata = Map.copyOf(metadata);
                } catch (Exception ignored) {
                    marketDataText += "\n[키움 보조 근거 확인 불가. KIS의 확인된 자료는 유지한다.]\n";
                }
                checkBudget(deadline);
            }
        }
        if (!boundedEvidenceCodes.isEmpty()) {
            checkBudget(deadline);
            try {
                if (kisExtendedEvidenceService != null)
                    marketDataText += Objects.requireNonNullElse(
                        kisExtendedEvidenceService.fetchFormattedExtendedEvidence(boundedEvidenceCodes), "");
            } catch (Exception ignored) {
                marketDataText += "\n[KIS 확장 증거 확인 불가. 수급·분봉 수치를 추정하지 않는다.]\n";
            }
            checkBudget(deadline);
            try {
                if (dartApiService != null)
                    marketDataText += Objects.requireNonNullElse(
                        dartApiService.fetchFormattedEventEvidence(boundedEvidenceCodes), "");
            } catch (Exception ignored) {
                marketDataText += "\n[DART 이벤트 증거 확인 불가. 공시 사실을 추정하지 않는다.]\n";
            }
            checkBudget(deadline);
        }

        String baseSystemPrompt = SYSTEM_PROMPT + "\n\n\uC624\uB298 \uB0A0\uC9DC: " + today + " (KST). \uBC18\uB4DC\uC2DC \uC774 \uB0A0\uC9DC \uAE30\uC900\uC73C\uB85C \uCD5C\uC2E0 \uC815\uBCF4\uB97C \uBD84\uC11D\uD560 \uAC83.\n\n"
            + "**\u26A0\uFE0F \uAC00\uACA9 \uADDC\uCE59 (\uCD5C\uC6B0\uC120)**:\n"
            + "- \uC785\uB825\uC5D0 \"\uC2E4\uC2DC\uAC04 \uC2DC\uC7A5 \uB370\uC774\uD130\" \uB610\uB294 \"\uC2E4\uC2DC\uAC04 \uAC00\uACA9\"\uC774 \uD3EC\uD568\uB418\uC5B4 \uC788\uC73C\uBA74, \uD574\uB2F9 \uAC00\uACA9\uC774 \uC720\uC77C\uD55C \uC815\uD655\uD55C \uD604\uC7AC\uAC00\uC785\uB2C8\uB2E4.\n"
            + "- \uD559\uC2B5 \uB370\uC774\uD130\uC758 \uACFC\uAC70 \uAC00\uACA9\uC744 \uD604\uC7AC\uAC00\uB85C \uC0AC\uC6A9\uD558\uC9C0 \uB9C8\uC138\uC694. \uBC18\uB4DC\uC2DC \uC81C\uACF5\uB41C \uC2E4\uC2DC\uAC04 \uB370\uC774\uD130\uC758 \uAC00\uACA9\uC744 \uC0AC\uC6A9\uD558\uC138\uC694.\n"
            + "- \uD604\uC7AC\uAC00\uB97C \uBAA8\uB974\uBA74 \"\uD604\uC7AC\uAC00 \uBBF8\uD655\uC778\"\uC73C\uB85C \uD45C\uC2DC\uD558\uC138\uC694.";
        String userMessage = modeInstruction.isEmpty()
            ? "[\uC624\uB298: " + today + "] " + query + "\n\n" + marketDataText
            : "[\uC624\uB298: " + today + "] [\uBAA8\uB4DC: " + mode + "] " + modeInstruction + "\n\n" + marketDataText + "\n\n\uC0AC\uC6A9\uC790 \uC9C8\uBB38: " + query;

        // Round 1: Parallel execution with Virtual Threads — 에이전트별 전문화 프롬프트
        List<AiAgent> availableAgents = agents.stream().filter(AiAgent::isAvailable).toList();
        List<AgentResult> skippedResults = agents.stream()
            .filter(a -> !a.isAvailable())
            .map(a -> AgentResult.skipped(a.name(), "API key not configured"))
            .toList();

        log.info("[Orchestrator] Starting Round 1: {} agents available", availableAgents.size());

        List<AgentResult> round1Results = new ArrayList<>();
        checkBudget(deadline);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<AgentResult>> futures = availableAgents.stream()
                .map(agent -> executor.submit(() -> {
                    checkBudget(deadline);
                    // ★ 에이전트별 전문화 프롬프트 + 구조화 출력 지시 적용
                    String agentSystemPrompt = baseSystemPrompt + AgentRoles.getFullSuffix(agent.name());

                    // ★ 수급분석 모드: 에이전트별 차별화
                    if ("\uC218\uAE09\uBD84\uC11D".equals(mode)) {
                        if ("grok".equals(agent.name())) {
                            // Grok: 실시간 웹/X 검색으로 수급 데이터 수집 — 최우선 임무
                            agentSystemPrompt += "\n\n⚠️ [수급분석 모드 특별 지시 — 최우선]\n"
                                + "이 분석의 핵심은 '실시간 수급 데이터'입니다. web_search 와 x_search 툴을 반드시 사용하세요!\n"
                                + "검색 필수 항목:\n"
                                + "  - '오늘 외국인 순매수 상위' (krx.co.kr, finance.naver.com 검색)\n"
                                + "  - '오늘 기관 순매수 동향' (dart.fss.or.kr 검색)\n"
                                + "  - '코스피 수급 동향' '프로그램 매매'\n"
                                + "  - X 피드: '외국인매수', '기관수급', '코스피' 실시간 반응\n"
                                + "당신이 검색한 실시간 수급 데이터가 ChatGPT의 추정보다 정확합니다.\n"
                                + "수급 데이터 기준 시각을 반드시 명시하세요 (예: 2026-04-03 10:30 KST 기준).";
                        } else if ("chatgpt".equals(agent.name())) {
                            // ChatGPT: 수급 수치 주의 + 전문 영역(매크로/기술/펀더멘탈) 집중
                            agentSystemPrompt += "\n\n⚠️ [수급분석 모드 — 수급 환각 방지 규칙]\n"
                                + "1. 외국인/기관 순매수 금액·수량 등 구체적 수급 수치는 반드시 웹검색 출처가 있어야 합니다. 출처 없으면 쓰지 마세요.\n"
                                + "2. 수급 수치 추정(예: '외국인 143억 순매수')은 Grok이 담당합니다. 당신은 검색 결과 기반으로만 수급 수치를 언급하세요.\n"
                                + "3. 당신의 수급분석 기여 영역:\n"
                                + "   - 매크로 환경이 수급에 미치는 구조적 영향 (금리/환율 → 외국인 자금 흐름)\n"
                                + "   - 밸류에이션 관점에서 기관 매수 유인 분석 (PER/PBR 저평가 여부)\n"
                                + "   - DART 공시 데이터 기반 수급 유발 재료 분석\n"
                                + "   - 기술적 분석: 차트에서 수급 전환 신호 (거래량 급증, 캔들 패턴)\n"
                                + "4. 시장이 -3% 이상 급락 중이면 매수 추천보다 리스크 관리(관망/손절/헤지)를 우선 제시하세요.";
                        }
                    }

                    log.info("[Orchestrator] {} starting (specialized)...", agent.name());
                    // ★ v2.8.3: Grok 은 모드별 SearchPolicy 적용 (BREAKOUT 검색 OFF + 저렴 모델)
                    //   ChatGPT 등 다른 에이전트는 기본 analyze() 유지
                    AgentResult result;
                    if (agent instanceof GrokAgent grok) {
                        result = grok.analyzeForMode(agentSystemPrompt, userMessage, mode);
                        // ★ v2.8.6 (2026-04-29): Grok 실패 시 Gemini 자동 폴백
                        //   xAI team_blocked / 401 / network error 등으로 grok 이 success 외 status 반환 시
                        //   Gemini 가 동일 프롬프트로 재분석. 토큰 비용 추가지만 분석 누락 방지.
                        if (geminiFallback != null && geminiFallback.isAvailable()
                                && !"success".equals(result.status())) {
                            log.warn("[Orchestrator] grok failed (status={}, {}ms) → Gemini 폴백 시작",
                                result.status(), result.durationMs());
                            checkBudget(deadline);
                            AgentResult fallback = geminiFallback.analyze(agentSystemPrompt, userMessage);
                            log.info("[Orchestrator] gemini fallback finished ({}ms, status={})",
                                fallback.durationMs(), fallback.status());
                            result = fallback;
                        }
                    } else {
                        result = agent.analyze(agentSystemPrompt, userMessage);
                    }
                    log.info("[Orchestrator] {} finished ({}ms, status={})",
                        agent.name(), result.durationMs(), result.status());
                    return result;
                }))
                .toList();

            for (Future<AgentResult> future : futures) {
                try {
                    checkBudget(deadline);
                    round1Results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                } catch (java.util.concurrent.TimeoutException e) {
                    future.cancel(true);
                    throw new CancellationException("Analysis execution deadline exceeded");
                } catch (InterruptedException e) {
                    future.cancel(true);
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Analysis execution interrupted");
                } catch (Exception e) {
                    future.cancel(true);
                    log.error("[Orchestrator] Agent execution failed ({})", e.getClass().getSimpleName());
                }
            }
        } finally {
            executor.shutdownNow();
        }
        checkBudget(deadline);

        round1Results.addAll(skippedResults);

        List<AgentResult> successResults = round1Results.stream()
            .filter(r -> "success".equals(r.status()) && r.content() != null && !r.content().isBlank())
            .toList();

        // Parse once, then gate every public representation against tool candidates and verified quotes.
        Map<String, StructuredAnalysis> parsedResults = new LinkedHashMap<>();
        Map<String, List<StockPick>> agentPicks = new LinkedHashMap<>();
        for (AgentResult result : successResults) {
            StructuredAnalysis parsed = StructuredAnalysisParser.parse(result.content());
            MonitorController.recordParseResult(result.agent(), parsed != null);
            if (parsed != null) parsedResults.putIfAbsent(result.agent(), parsed);
            List<StockPick> picks = parsed != null
                ? parsed.stocks().stream().map(stock -> new StockPick(stock.name(), stock.code(),
                    formatPrice(stock.currentPrice()), formatPrice(stock.targetPrice()), formatPrice(stock.stopLoss()),
                    stock.action(), String.join("; ", stock.reasons()))).toList()
                : (result.content().contains("```json:analysis") ? List.of() : StockParser.parseStockPicks(result.content()));
            var inspection = StructuredAnalysisParser.inspect(result.content());
            log.info("[PublicationGate] mode={} agent={} schema={} declared={} accepted={} draft={}",
                Set.of("BREAKOUT", "REVERSAL_EDGE", "FLOW_LEADER", "CATALYST_BURST").contains(mode) ? mode : "OTHER",
                Set.of("chatgpt", "deepseek", "grok", "gemini").contains(result.agent()) ? result.agent() : "OTHER",
                inspection.status(), inspection.declaredStocks(), inspection.acceptedStocks(), picks.size());
            agentPicks.putIfAbsent(result.agent(), picks);
        }
        Map<String, StockPick> draftPicks = new LinkedHashMap<>();
        agentPicks.entrySet().stream().sorted(Comparator.comparingInt(entry -> "chatgpt".equals(entry.getKey()) ? 0 : 1))
            .forEach(entry -> entry.getValue().forEach(pick -> {
                if (candidateSources.containsKey(pick.code())) draftPicks.putIfAbsent(pick.code(), pick);
            }));
        Map<String, String> quotes = new HashMap<>(queryStockPrices);
        if (!draftPicks.isEmpty()) {
            try {
                quotes.putAll(marketDataService.fetchStockPrices(new ArrayList<>(draftPicks.keySet())));
            } catch (Exception e) {
                log.warn("[Orchestrator] Quote verification unavailable; unquoted candidates excluded");
            }
        }
        quotes.entrySet().removeIf(entry -> parseKoreanPrice(entry.getValue()) <= 0);
        Map<String,String> quoteSources = new HashMap<>();
        quotes.keySet().forEach(code -> quoteSources.put(code, "NAVER_FINANCE"));
        String kiwoomQuoteSource=activeKiwoomSource();
        if (!kiwoomQuoteSource.isEmpty()) {
            List<String> missing = draftPicks.keySet().stream().filter(code -> !quotes.containsKey(code))
                .limit(com.bitman.justbuy.service.KiwoomSupplementPolicy.CANDIDATE_LIMIT).toList();
            if (!missing.isEmpty()) {
                checkBudget(deadline);
                try {
                    var supplementaryQuotes = kiwoomSupplement.lookupAll(missing);
                    Instant receivedAt = Instant.now();
                    for (String code : missing) {
                        var quote = supplementaryQuotes.get(code);
                        if (quote == null || !code.equals(quote.code()) || !kiwoomQuoteSource.equals(quote.source())
                            || !Objects.equals(candidateNames.get(code), quote.name()) || quote.fetchedAt() == null
                            || quote.fetchedAt().isAfter(receivedAt) || quote.fetchedAt().isBefore(receivedAt.minusSeconds(60))
                            || quote.price() == null || quote.price().signum() <= 0) continue;
                        try {
                            long price = quote.price().toBigIntegerExact().longValueExact();
                            if (price <= 0) continue;
                            quotes.put(code, String.format("%,d", price));
                            quoteSources.put(code, quote.source());
                        } catch (ArithmeticException ignored) { /* A fractional or out-of-range won price is unavailable. */ }
                    }
                } catch (Exception ignored) {
                    log.warn("[PublicationGate] Auxiliary quote unavailable; unquoted candidates remain excluded");
                }
                checkBudget(deadline);
            }
        }
        String quoteCheckedAt = Instant.now().toString(); // retrieval validation time, not exchange trade time
        Map<String, StockPick> publishedPicks = new LinkedHashMap<>();
        for (StockPick pick : draftPicks.values()) {
            checkBudget(deadline);
            long current = parseKoreanPrice(quotes.get(pick.code()));
            String candidateName = candidateNames.get(pick.code());
            if (current <= 0 || candidateName == null || candidateName.isBlank() || publishedPicks.size() >= 10) continue;
            FinancialScore financial;
            try {
                financial = financialScorer.score(pick.code());
            } catch (Exception e) {
                financial = FinancialScore.empty();
            }
            if (financial == null) financial = FinancialScore.empty();
            publishedPicks.put(pick.code(), new StockPick(candidateName, pick.code(), String.format("%,d", current),
                validatePrice(pick.targetPrice(), current, pick.name(), "목표가"),
                validatePrice(pick.stopLoss(), current, pick.name(), "손절가"), pick.action(), pick.reason(),
                financial.score(), financial.summary(), candidateSources.get(pick.code()), quoteSources.get(pick.code()), quoteCheckedAt));
        }
        Map<String, StructuredAnalysis> verifiedResults = new LinkedHashMap<>();
        for (var entry : parsedResults.entrySet()) {
            var verified = entry.getValue().stocks().stream().filter(stock -> publishedPicks.containsKey(stock.code()))
                .map(stock -> {
                    StockPick pick = publishedPicks.get(stock.code());
                    long current = parseKoreanPrice(pick.currentPrice());
                    return new StructuredAnalysisParser.StructuredStock(pick.name(), stock.code(), stock.action(), stock.confidence(),
                        current, positivePrice(validatePrice(formatPrice(stock.targetPrice()), current, stock.name(), "목표가")),
                        positivePrice(validatePrice(formatPrice(stock.stopLoss()), current, stock.name(), "손절가")),
                        stock.scenario(), stock.reasons(), stock.financialScore(), stock.financialSummary());
                }).toList();
            if (!verified.isEmpty()) verifiedResults.put(entry.getKey(), new StructuredAnalysis(verified,
                entry.getValue().marketSentiment(), entry.getValue().keyRisks()));
        }
        ConsensusResult consensus = null;
        if (!verifiedResults.isEmpty()) {
            try {
                consensus = consensusEngine.calculateConsensus(verifiedResults, collectKisSupplyData(verifiedResults), fetchMarketGate());
            } catch (Exception e) {
                log.warn("[Orchestrator] Verified consensus unavailable");
            }
        }
        List<AgentResult> publicResults = new ArrayList<>();
        for (AgentResult result : round1Results) {
            List<StockPick> verified = agentPicks.getOrDefault(result.agent(), List.of()).stream()
                .filter(pick -> publishedPicks.containsKey(pick.code()))
                .map(pick -> {
                    StockPick published = publishedPicks.get(pick.code());
                    long current = parseKoreanPrice(published.currentPrice());
                    return new StockPick(published.name(), pick.code(), published.currentPrice(),
                        validatePrice(pick.targetPrice(), current, pick.name(), "목표가"),
                        validatePrice(pick.stopLoss(), current, pick.name(), "손절가"), pick.action(), pick.reason(),
                        published.financialScore(), published.financialSummary(), published.candidateSource(),
                        published.quoteSource(), published.quoteCheckedAt());
                }).distinct().toList();
            publicResults.add(new AgentResult(result.agent(), "success".equals(result.status()) ? renderPublishedPicks(verified) : "",
                result.model(), result.inputTokens(), result.outputTokens(), result.status(), result.error(), result.durationMs(),
                result.thoughtTokens(), result.totalTokens()));
        }
        long totalDuration = System.currentTimeMillis() - totalStart;
        checkBudget(deadline);
        List<StockPick> finalPicks = List.copyOf(publishedPicks.values());
        Set<String> parsedCodes = new HashSet<>();
        agentPicks.values().forEach(picks -> picks.forEach(pick -> parsedCodes.add(pick.code())));
        long outsidePool = parsedCodes.stream().filter(code -> !candidateSources.containsKey(code)).count();
        long missingQuote = draftPicks.keySet().stream().filter(code -> parseKoreanPrice(quotes.get(code)) <= 0).count();
        long missingName = draftPicks.keySet().stream().filter(code -> candidateNames.get(code) == null || candidateNames.get(code).isBlank()).count();
        log.info("[PublicationGate] mode={} candidates={} parsedCodes={} outsidePool={} eligible={} missingQuote={} missingName={} published={}",
            Set.of("BREAKOUT", "REVERSAL_EDGE", "FLOW_LEADER", "CATALYST_BURST").contains(mode) ? mode : "OTHER",
            candidateSources.size(), parsedCodes.size(), outsidePool, draftPicks.size(), missingQuote, missingName, finalPicks.size());
        // The caller persists and tracks only its final publication after all periodic filters.
        return new AnalysisResponse(mode, query, publicResults, null, renderPublishedPicks(finalPicks), finalPicks, consensus,
            Instant.now().toString(), !finalPicks.isEmpty(),
            new AnalysisResponse.Metadata(totalDuration, availableAgents.size(), successResults.size(),null,sourceExpansionMetadata));
    }

    private static void collectCandidateRows(String text, String source, Map<String, String> out, Map<String, String> names) {
        if (text == null) return;
        var matcher = java.util.regex.Pattern.compile("[（(]([0-9]{6})[）)]").matcher(text);
        while (matcher.find()) {
            int lineStart = text.lastIndexOf('\n', matcher.start()) + 1;
            String name = text.substring(lineStart, matcher.start()).trim();
            int colon = name.lastIndexOf(':');
            if (colon >= 0) name = name.substring(colon + 1).trim();
            name = name.replaceFirst("^(?:[★📌•*\\-]\\s*|[0-9]+[.)]\\s*)+", "").trim();
            if (name.isBlank()) continue;
            out.putIfAbsent(matcher.group(1), source);
            names.putIfAbsent(matcher.group(1), name);
        }
    }

    private static void checkBudget(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
            throw new CancellationException("Analysis execution interrupted or deadline exceeded");
    }

    private static String formatPrice(Long price) {
        return price == null || price <= 0 ? "미확인" : String.format("%,d", price);
    }

    private static Long positivePrice(String price) {
        long amount = parseKoreanPrice(price);
        return amount > 0 ? amount : null;
    }

    private static String renderPublishedPicks(List<StockPick> picks) {
        if (picks.isEmpty()) return "후보 소속과 현재가 검증을 통과한 종목이 없습니다. 데이터를 확인한 뒤 다시 분석해 주세요.";
        StringBuilder content = new StringBuilder("검증된 후보의 조건 분석\n");
        for (StockPick pick : picks) {
            content.append("\n📌 ").append(pick.name()).append(" (").append(pick.code()).append(") — 현재가 ")
                .append(pick.currentPrice()).append("원\n")
                .append("판단: ").append(pick.action()).append("\n근거: ").append(pick.reason())
                .append("\n목표가: ").append(pick.targetPrice()).append(" / 손절가: ").append(pick.stopLoss())
                .append("\n후보 출처: ").append(pick.candidateSource()).append(" / 시세 출처: ").append(pick.quoteSource())
                .append(" / 시세 확인 시각: ").append(pick.quoteCheckedAt()).append("\n");
        }
        return content.toString();
    }

    /** 한국식 가격 문자열 → long 변환 ("191,100" → 191100, "약 36,000원" → 36000) */
    private static long parseKoreanPrice(String priceStr) {
        if (priceStr == null || priceStr.isBlank()) return 0;
        String clean = priceStr.trim().replaceFirst("원$", "").trim();
        if (!clean.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)")) return 0;
        try { return Long.parseLong(clean.replace(",", "")); } catch (NumberFormatException e) { return 0; }
    }

    private String validatePrice(String priceStr, long currentPrice, String stockName, String label) {
        long price = parseKoreanPrice(priceStr);
        if (price <= 0 || currentPrice <= 0) return "미확인";
        double ratio = (double) price / currentPrice;
        if (ratio < 0.5 || ratio > (label.contains("목표") ? 2.0 : 1.0)) return "미확인";
        return String.format("%,d", price);
    }

    /**
     * 쿼리에서 종목명을 찾아 실시간 가격을 가져온다.
     * 반환: 프롬프트에 추가할 텍스트 (비어있으면 해당 종목 없음)
     */
    private String fetchQueryStockPrices(String query, Map<String, String> outPrices) {
        if (query == null || query.isBlank()) return "";

        // KNOWN_STOCKS에서 쿼리에 포함된 종목명 찾기
        Set<String> foundCodes = new LinkedHashSet<>();
        Map<String, String> codeToName = new LinkedHashMap<>();

        for (var entry : StockParser.KNOWN_STOCKS.entrySet()) {
            if (query.contains(entry.getKey())) {
                String code = entry.getValue();
                if (foundCodes.add(code)) {
                    codeToName.put(code, entry.getKey());
                }
            }
        }

        if (foundCodes.isEmpty()) return "";

        // 실시간 가격 조회
        Map<String, String> prices = marketDataService.fetchStockPrices(new ArrayList<>(foundCodes));
        prices.entrySet().stream().filter(entry -> foundCodes.contains(entry.getKey()) && parseKoreanPrice(entry.getValue()) > 0)
            .forEach(entry -> outPrices.put(entry.getKey(), entry.getValue()));

        if (prices.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n\u2501\u2501\u2501 \ubd84\uc11d \ub300\uc0c1 \uc885\ubaa9 \uc2e4\uc2dc\uac04 \uac00\uaca9 \u2501\u2501\u2501\n");
        sb.append("\u26a0\ufe0f \uc544\ub798\ub294 \ub124\uc774\ubc84\uae08\uc735 \uc2e4\uc2dc\uac04 \uc2dc\uc138\uc785\ub2c8\ub2e4. \ubd84\uc11d \uc2dc \ubc18\ub4dc\uc2dc \uc774 \uac00\uaca9\uc744 \ud604\uc7ac\uac00\ub85c \uc0ac\uc6a9\ud558\uc138\uc694!\n");
        for (var entry : prices.entrySet()) {
            String code = entry.getKey();
            String price = entry.getValue();
            String name = codeToName.getOrDefault(code, code);
            sb.append("  \u2605 ").append(name).append("(").append(code).append("): \ud604\uc7ac\uac00 ")
              .append(price).append("\uc6d0 (\ub124\uc774\ubc84 \uc2e4\uc2dc\uac04)\n");
        }
        sb.append("\u2501\u2501\u2501 \uc704 \uac00\uaca9\uc744 \ubc18\ub4dc\uc2dc \ud604\uc7ac\uac00\ub85c \uc0ac\uc6a9\ud558\uc138\uc694! \u2501\u2501\u2501\n");
        log.info("[Orchestrator] 쿼리 종목 가격 주입: {}", prices);
        return sb.toString();
    }

    /**
     * DART 재무/공시 데이터를 종목코드 세트에 대해 수집.
     * 최대 5종목, 실패 시 빈 문자열 (분석 중단 없음).
     */
    private String fetchDartDataForStocks(Set<String> stockCodes) {
        if (stockCodes.isEmpty() || dartApiService == null || !dartApiService.isAvailable()) return "";

        try {
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (String code : stockCodes) {
                if (count >= 5) break;
                String dartData = dartApiService.fetchFormattedDartData(code);
                if (!dartData.isEmpty()) {
                    sb.append(dartData);
                    count++;
                }
            }
            if (count > 0) {
                log.info("[Orchestrator] DART 재무 데이터 주입: {}개 종목", count);
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[Orchestrator] DART 데이터 수집 실패: {}", e.getMessage());
            return "";
        }
    }

    /** KIS 실시간 시세 + 수급 데이터 수집 */
    private String fetchKisData(Set<String> queryCodes, String mode) {
        if (kisApiService == null || !kisApiService.isAvailable()) return "";

        try {
            StringBuilder sb = new StringBuilder();

            // ★ 1) 전체 시장 순위 데이터 (모든 모드에 주입)
            //    - 시장 지수 (코스피/코스닥)
            //    - 거래량 순위 TOP 20
            //    - 외국인 순매수 순위 TOP 15
            String allRankings = kisApiService.fetchAllRankings();
            if (!allRankings.isEmpty()) sb.append(allRankings);

            // 2) 쿼리 종목 개별 시세 + 수급
            int count = 0;
            for (String code : queryCodes) {
                if (count >= 5) break;
                String kisData = kisApiService.fetchFormattedKisData(code);
                if (!kisData.isEmpty()) {
                    sb.append(kisData);
                    count++;
                }
            }

            // 3) 수급분석 모드: 시총 상위 종목 수급 요약 추가
            if ("\uC218\uAE09\uBD84\uC11D".equals(mode)) {
                List<String> topCodes = List.of(
                    "005930", "000660", "373220", "207940", "005380",
                    "006400", "051910", "035420", "000270", "068270",
                    "105560", "055550", "035720", "003670", "028260",
                    "096770", "034730", "066570", "032830", "003550"
                );
                String supply = kisApiService.fetchTopStocksSupplySummary(topCodes);
                if (!supply.isEmpty()) sb.append(supply);
            }

            log.info("[Orchestrator] KIS 데이터 주입: rankings={}, 종목={}개, mode={}",
                !allRankings.isEmpty(), count, mode);
            return sb.toString();
        } catch (Exception e) {
            log.warn("[Orchestrator] KIS 데이터 수집 실패: {}", e.getMessage());
            return "";
        }
    }

    @org.springframework.beans.factory.annotation.Value("${bitman.flask.base-url:http://localhost:5001}")
    private String flaskBaseUrl;

    /** Flask API에서 시장 레짐(Market Gate) 상태를 가져온다. 실패 시 YELLOW 기본값. */
    private String fetchMarketGate() {
        try {
            String url = flaskBaseUrl + "/api/kr/market-gate";
            ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                JsonNode root = objectMapper.readTree(resp.getBody());
                return root.path("gate").asText("YELLOW");
            }
        } catch (Exception e) {
            log.warn("[Orchestrator] Market gate fetch failed, defaulting to YELLOW: {}", e.getMessage());
        }
        return "YELLOW";
    }

    /** 구조화 분석 결과에서 종목코드를 추출하고 KIS 수급 데이터를 수집한다. (최대 10종목) */
    private Map<String, Map<String, String>> collectKisSupplyData(Map<String, StructuredAnalysis> results) {
        Set<String> codes = new LinkedHashSet<>();
        for (var analysis : results.values()) {
            for (var stock : analysis.stocks()) {
                if (stock.code() != null && stock.code().length() == 6) {
                    codes.add(stock.code());
                }
            }
        }
        Map<String, Map<String, String>> supplyData = new LinkedHashMap<>();
        int count = 0;
        for (String code : codes) {
            if (count >= 10) break;
            try {
                Map<String, String> priceData = kisApiService.fetchCurrentPrice(code);
                if (!priceData.isEmpty()) {
                    supplyData.put(code, priceData);
                }
            } catch (Exception e) {
                log.debug("[Orchestrator] KIS supply data fetch failed for {}: {}", code, e.getMessage());
            }
            count++;
        }
        return supplyData;
    }

}
