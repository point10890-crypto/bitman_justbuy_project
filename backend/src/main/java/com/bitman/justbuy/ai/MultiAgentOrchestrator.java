package com.bitman.justbuy.ai;

import com.bitman.justbuy.ai.agent.AiAgent;
import com.bitman.justbuy.controller.MonitorController;
import com.bitman.justbuy.dto.AgentResult;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.ConsensusResult;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.service.DartApiService;
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
    private final SynthesisEngine synthesisEngine;
    private final MarketDataService marketDataService;
    private final ConsensusEngine consensusEngine;
    private final DartApiService dartApiService;
    private final KisApiService kisApiService;
    private final TrackRecordService trackRecordService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public MultiAgentOrchestrator(List<AiAgent> agents, SynthesisEngine synthesisEngine,
                                   MarketDataService marketDataService, ConsensusEngine consensusEngine,
                                   DartApiService dartApiService, KisApiService kisApiService,
                                   TrackRecordService trackRecordService,
                                   RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.agents = agents;
        this.synthesisEngine = synthesisEngine;
        this.marketDataService = marketDataService;
        this.consensusEngine = consensusEngine;
        this.dartApiService = dartApiService;
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
        + "* 웹검색이 불가능한 환경이라면, 학습 데이터 기반으로 구체적 종목명·가격대·근거를 제시. 회피 답변 금지\n\n"
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
            + "\"데이터 접근 불가\" 같은 회피 답변 금지.");

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
            + "\"데이터에 접근할 수 없다\"는 회피 답변 금지. 학습 데이터 기반으로라도 구체적 가격대와 근거를 반드시 제시할 것.");

        // ─── 4 NEW CONCEPT MODES ─────────────────────────────────────────
        m.put("BREAKOUT",
            "기술적 돌파 매수 관점에서 분석해줘.\n"
            + "오늘 장중 52주 신고가 돌파, 저항선 돌파, 거래량 급증 패턴 종목을 찾아:\n"
            + "\uD83D\uDCCC 종목명 (종목코드) \u2014 현재가 약 XX,XXX원\n"
            + "  - 돌파 근거: (어떤 저항선/고점을 돌파했는지, 거래량 급증 배율)\n"
            + "  - 이동평균 배열: (5/20/60선 정배열 여부)\n"
            + "  - 기술적 타겟: XX,XXX원 (돌파 후 목표 — 피보나치/전고점 기준)\n"
            + "  - 손절: XX,XXX원 (돌파 실패·되돌림 기준)\n"
            + "  - 수급 확인: 외국인/기관 돌파 동반 여부 (Grok 검색)\n"
            + "입력된 KIS 실시간 데이터에서 상승률·거래량 상위 종목 우선 선별. 최소 3종목 제시.\n"
            + "회피 답변 금지. 기술적 근거가 있는 한 반드시 구체적 종목을 제시할 것.");

        m.put("FLOW_LEADER",
            "외국인·기관 수급 주도 종목 분석.\n"
            + "쌍끌이(외국인+기관 동시매수), 연속 순매수, 수급 전환 종목을 찾아:\n"
            + "\uD83D\uDCCC 종목명 (종목코드) \u2014 현재가 약 XX,XXX원\n"
            + "  - 외국인: 순매수 X일 연속, 약 X억원 (Grok 검색·krx.co.kr 기준)\n"
            + "  - 기관: 순매수 X일 연속, 약 X억원 (Grok 검색 기준)\n"
            + "  - 수급 유형: 쌍끌이/외국인단독/기관유입/연기금매수/프로그램\n"
            + "  - 수급 전환 배경: (왜 외국인/기관이 매수하는지 매크로·뉴스 연결)\n"
            + "  - 목표가: XX,XXX원 (수급 지속 시)\n"
            + "  - 환율·매크로 영향: USD/KRW 방향이 외국인 자금에 미치는 영향 (ChatGPT)\n"
            + "KIS 실시간 데이터 + Grok 실시간 검색 기반. 수급 수치는 출처 명시 필수.\n"
            + "⚠️ 수급 수치는 Grok 검색 결과만 사용. 출처 없는 수치 환각 금지!");

        m.put("CATALYST_BURST",
            "재료/이벤트 드리븐 급등 후보 분석.\n"
            + "오늘 공시(DART), 뉴스, 정책 발표, 실적 서프라이즈 등 강력한 재료 종목:\n"
            + "\uD83D\uDCCC 종목명 (종목코드) \u2014 현재가 약 XX,XXX원\n"
            + "  - 핵심 재료: (공시/뉴스 내용, 발생 시간 KST 명시)\n"
            + "  - 재료 강도: ★★★(구조적·반복) / ★★(단기·이벤트) / ★(1회성)\n"
            + "  - 가격 반영도: (재료가 주가에 얼마나 반영됐는지 — 선반영/후반영 판단)\n"
            + "  - 연관 테마: (동반 상승 가능한 섹터·테마 종목)\n"
            + "  - 목표가: XX,XXX원 / 손절: XX,XXX원\n"
            + "  - ChatGPT DART 공시 분석 + Grok 실시간 뉴스 검색 결합\n"
            + "재료가 있는 종목부터 우선. 재료 발생 시간 반드시 명시. 회피 금지.");

        m.put("REVERSAL_EDGE",
            "역발상 반전 매수 후보 분석.\n"
            + "과매도 구간, 숏스퀴즈 가능, X피드 공포 극대화 후 반전 징후 종목:\n"
            + "\uD83D\uDCCC 종목명 (종목코드) \u2014 현재가 약 XX,XXX원\n"
            + "  - 과매도 지표: RSI(30 이하)/스토캐스틱 수치, 52주 저점 대비 낙폭(%)\n"
            + "  - 반전 신호: (캔들 패턴 — 망치형/역망치/피어싱라인, 거래량 급증)\n"
            + "  - X피드 심리: (Grok X검색 — 공포 극대화 문구, 패닉 셀 분위기)\n"
            + "  - 숏스퀴즈 가능성: 공매도 비율 + 대차잔고 (Grok 검색)\n"
            + "  - 컨센서스 역발상: (다수가 약세를 볼 때 강세 근거)\n"
            + "  - 목표가: XX,XXX원 / 손절: XX,XXX원\n"
            + "  - Grok X피드 센티먼트 + ChatGPT 기술적 과매도 판단 결합\n"
            + "⚠️ 시장 전체 급락(-3% 이상)이면 개별 역발상보다 전체 반전 신호를 먼저 진단할 것.");

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
        String today = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.of("Asia/Seoul")).format(Instant.now());

        String modeInstruction = MODE_PROMPTS.getOrDefault(mode, "");

        // Fetch market data
        String marketDataText;
        try {
            marketDataText = marketDataService.fetchFormattedMarketData();
        } catch (Exception e) {
            log.warn("Failed to fetch market data: {}", e.getMessage());
            marketDataText = "\n[\uC8FC\uC758: \uC2E4\uC2DC\uAC04 \uC2DC\uC7A5 \uB370\uC774\uD130 \uC218\uC9D1\uC5D0 \uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4. \uD559\uC2B5 \uB370\uC774\uD130 \uAE30\uBC18\uC73C\uB85C \uBD84\uC11D\uD569\uB2C8\uB2E4.]\n";
        }

        // ★ 쿼리에 포함된 종목의 실시간 가격을 추가 수집하여 프롬프트에 포함
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
        String dartText = fetchDartDataForStocks(queryCodes);
        if (!dartText.isEmpty()) {
            marketDataText += dartText;
        }

        // ★ KIS 실시간 시세 + 수급 데이터 추가
        String kisText = fetchKisData(queryCodes, mode);
        if (!kisText.isEmpty()) {
            marketDataText += kisText;
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
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<AgentResult>> futures = availableAgents.stream()
                .map(agent -> executor.submit(() -> {
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
                    AgentResult result = agent.analyze(agentSystemPrompt, userMessage);
                    log.info("[Orchestrator] {} finished ({}ms, status={})",
                        agent.name(), result.durationMs(), result.status());
                    return result;
                }))
                .toList();

            for (Future<AgentResult> future : futures) {
                try {
                    round1Results.add(future.get(180, TimeUnit.SECONDS));
                } catch (java.util.concurrent.TimeoutException e) {
                    future.cancel(true);
                    log.error("[Orchestrator] Agent timeout (180s), cancelled");
                } catch (Exception e) {
                    future.cancel(true);
                    log.error("[Orchestrator] Agent failed: {}", e.getMessage(), e);
                }
            }
        }

        round1Results.addAll(skippedResults);

        List<AgentResult> successResults = round1Results.stream()
            .filter(r -> "success".equals(r.status()) && r.content() != null && !r.content().isBlank())
            .toList();

        // ★ 구조화 출력 파싱 + 합의 엔진
        Map<String, StructuredAnalysis> structuredResults = new LinkedHashMap<>();
        for (AgentResult r : successResults) {
            StructuredAnalysis parsed = StructuredAnalysisParser.parse(r.content());
            if (parsed != null) {
                structuredResults.put(r.agent(), parsed);
                MonitorController.recordParseResult(r.agent(), true);
                log.info("[Orchestrator] {}: 구조화 출력 파싱 성공 ({}개 종목)", r.agent(), parsed.stocks().size());
            } else {
                MonitorController.recordParseResult(r.agent(), false);
                log.info("[Orchestrator] {}: 구조화 출력 없음 → 기존 파서 폴백", r.agent());
            }
        }

        ConsensusResult consensus = null;
        String consensusText = "";
        if (structuredResults.size() >= 2) {
            try {
                String marketGate = fetchMarketGate();
                Map<String, Map<String, String>> kisSupplyData = collectKisSupplyData(structuredResults);
                consensus = consensusEngine.calculateConsensus(structuredResults, kisSupplyData, marketGate);
                consensusText = consensusEngine.formatForSynthesis(consensus);
                log.info("[Orchestrator] 합의 엔진: 전체 합의도 {}%, {}개 종목, {}개 이견",
                    consensus.agreementScore(), consensus.stocks().size(), consensus.divergences().size());
            } catch (Exception e) {
                log.warn("[Orchestrator] 합의 엔진 오류 (분석은 계속): {}", e.getMessage());
                consensus = null;
                consensusText = "";
            }
        }

        // ★ Synthesis 전에 1차 종목 추출 + 실시간 가격 수집 (stock project 패턴)
        List<StockPick> preSynthesisPicks = new ArrayList<>();
        for (AgentResult r : successResults) {
            List<StockPick> morePicks = StockParser.parseStockPicks(r.content());
            for (StockPick p : morePicks) {
                if (preSynthesisPicks.stream().noneMatch(sp -> sp.code().equals(p.code()))) {
                    preSynthesisPicks.add(p);
                }
            }
        }

        // 실시간 가격 미리 수집 → Synthesis prompt에 주입
        Map<String, String> preSynthesisPrices = new HashMap<>(queryStockPrices);
        if (!preSynthesisPicks.isEmpty()) {
            try {
                List<String> preCodes = preSynthesisPicks.stream()
                    .map(StockPick::code).filter(c -> c.length() == 6).toList();
                Map<String, String> fetched = marketDataService.fetchStockPrices(preCodes);
                preSynthesisPrices.putAll(fetched);
                log.info("[Orchestrator] Pre-synthesis 가격 수집: {}개 종목", fetched.size());
            } catch (Exception e) {
                log.warn("[Orchestrator] Pre-synthesis 가격 수집 실패: {}", e.getMessage());
            }
        }

        // Round 2: Claude synthesis — 합의 데이터 + 실시간 가격 포함
        String finalContent;
        AgentResult synthesisResult = null;
        if (successResults.size() >= 2 && synthesisEngine.isAvailable()) {
            log.info("[Orchestrator] Starting Round 2: Synthesis ({} results, {} verified prices)",
                successResults.size(), preSynthesisPrices.size());
            try {
                synthesisResult = synthesisEngine.synthesizeWithResult(
                    successResults, query, mode, today, consensusText, preSynthesisPicks, preSynthesisPrices);
            } catch (Exception e) {
                log.warn("[Orchestrator] Synthesis 오류 (첫 번째 에이전트 결과로 폴백): {}", e.getMessage());
                synthesisResult = null;
            }
            finalContent = (synthesisResult != null && "success".equals(synthesisResult.status())
                && synthesisResult.content() != null && !synthesisResult.content().isBlank())
                ? synthesisResult.content() : successResults.getFirst().content();
        } else if (successResults.size() == 1) {
            finalContent = successResults.getFirst().content();
        } else if (successResults.isEmpty()) {
            finalContent = "\uBAA8\uB4E0 AI \uC5D4\uC9C4\uC774 \uC751\uB2F5\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4. API \uD0A4\uB97C \uD655\uC778\uD558\uACE0 \uC7A0\uC2DC \uD6C4 \uB2E4\uC2DC \uC2DC\uB3C4\uD574 \uC8FC\uC138\uC694.";
        } else {
            finalContent = successResults.getFirst().content();
        }

        // Extract stock picks
        List<StockPick> stockPicks = new ArrayList<>(StockParser.parseStockPicks(finalContent));
        if (stockPicks.size() < 2) {
            for (AgentResult r : successResults) {
                List<StockPick> morePicks = StockParser.parseStockPicks(r.content());
                for (StockPick p : morePicks) {
                    if (stockPicks.stream().noneMatch(sp -> sp.code().equals(p.code()))) {
                        stockPicks.add(p);
                    }
                }
            }
        }

        // ── STEP: 실시간 가격 교정 + 본문 교정 + 검증 푸터 ──
        Map<String, String> realPrices = new HashMap<>(queryStockPrices);
        if (!stockPicks.isEmpty()) {
            try {
                List<String> codes = stockPicks.stream()
                    .map(StockPick::code)
                    .filter(c -> c.length() == 6)
                    .toList();
                Map<String, String> fetchedPrices = marketDataService.fetchStockPrices(codes);
                realPrices.putAll(fetchedPrices);

                // 1) stockPick 현재가 교정
                List<StockPick> corrected = new ArrayList<>();
                for (StockPick pick : stockPicks) {
                    String realPrice = realPrices.get(pick.code());
                    corrected.add(realPrice != null
                        ? new StockPick(pick.name(), pick.code(), realPrice,
                            pick.targetPrice(), pick.stopLoss(), pick.action(), pick.reason())
                        : pick);
                }
                stockPicks = corrected;

                // 2) finalContent 본문에서 잘못된 가격 aggressive regex 교정
                for (StockPick pick : stockPicks) {
                    String realPrice = realPrices.get(pick.code());
                    if (realPrice == null || pick.name() == null) continue;
                    String nameEsc = java.util.regex.Pattern.quote(pick.name());
                    String codeEsc = java.util.regex.Pattern.quote(pick.code());

                    // 패턴1: "종목명 (코드) — 현재가 약 XX,XXX원"
                    finalContent = finalContent.replaceAll(
                        "(" + nameEsc + "\\s*[\\(（]?" + codeEsc + "[\\)）]?[^\\n]{0,50}?)"
                            + "(\ud604\uc7ac\uac00\\s*(?::\\s*)?(?:\uc57d\\s*)?)[0-9,]+(?:~[0-9,]+)?\\s*\uc6d0",
                        "$1$2" + realPrice + "\uc6d0");

                    // 패턴2: "종목명(코드)...약 XX,XXX원" (현재가 없이 "약" 으로 시작하는 가격)
                    finalContent = finalContent.replaceAll(
                        "(" + nameEsc + "\\s*[\\(（]" + codeEsc + "[\\)）][^\\n]{0,20}?)"
                            + "(\uc57d\\s*)[0-9,]+(?:~[0-9,]+)?\\s*\uc6d0",
                        "$1$2" + realPrice + "\uc6d0");

                    // 패턴3: "📌 종목명 (코드) — 현재가 약 XX원" (📌로 시작하는 라인)
                    finalContent = finalContent.replaceAll(
                        "(\uD83D\uDCCC\\s*" + nameEsc + "\\s*[\\(（]" + codeEsc + "[\\)）][^\\n]*?)"
                            + "(\ud604\uc7ac\uac00\\s*(?::\\s*)?(?:\uc57d\\s*)?)[0-9,]+(?:~[0-9,]+)?(?:\\s*\uc6d0)?",
                        "$1$2" + realPrice + "\uc6d0");

                    // 패턴4: "현재 주가: 약 XX,XXX원" (종목명 이후 같은 라인)
                    finalContent = finalContent.replaceAll(
                        "(" + nameEsc + "[^\\n]{0,60}?)"
                            + "(\ud604\uc7ac\\s*\uc8fc\uac00\\s*(?::\\s*)?(?:\uc57d\\s*)?)[0-9,]+(?:~[0-9,]+)?\\s*\uc6d0",
                        "$1$2" + realPrice + "\uc6d0");

                    // 패턴5: "현재가는 약 XX,XXX원" (종목명 이후 같은 라인)
                    finalContent = finalContent.replaceAll(
                        "(" + nameEsc + "[^\\n]{0,60}?)"
                            + "(\ud604\uc7ac\uac00\ub294?\\s*(?:\uc57d\\s*)?)[0-9,]+(?:~[0-9,]+)?\\s*\uc6d0",
                        "$1$2" + realPrice + "\uc6d0");
                }

                // 3) 실시간 검증 현재가 푸터 추가
                StringBuilder footer = new StringBuilder();
                for (StockPick pick : stockPicks) {
                    String realPrice = realPrices.get(pick.code());
                    if (realPrice != null) {
                        footer.append("  ").append(pick.name()).append("(").append(pick.code())
                              .append("): ").append(realPrice).append("\uc6d0\n");
                    }
                }
                if (!footer.isEmpty()) {
                    finalContent += "\n\n---\n\uD83D\uDCE1 **\uc2e4\uc2dc\uac04 \uac80\uc99d \ud604\uc7ac\uac00** (\ub124\uc774\ubc84\uae08\uc735 \uae30\uc900)\n"
                        + footer
                        + "\u203b \uc704 \ud604\uc7ac\uac00\ub294 \ub124\uc774\ubc84\uae08\uc735 \uc2e4\uc2dc\uac04 \uc2dc\uc138\ub85c \uac80\uc99d\ub41c \uac00\uaca9\uc785\ub2c8\ub2e4.";
                }
            } catch (Exception e) {
                log.warn("Price correction failed: {}", e.getMessage());
            }
        }

        long totalDuration = System.currentTimeMillis() - totalStart;
        log.info("[Orchestrator] Analysis complete: {}ms, {}/{} agents succeeded",
            totalDuration, successResults.size(), availableAgents.size());

        var response = new AnalysisResponse(
            mode, query, round1Results, synthesisResult, finalContent,
            stockPicks.stream().limit(10).toList(),
            consensus,
            Instant.now().toString(), true,
            new AnalysisResponse.Metadata(totalDuration, availableAgents.size(), successResults.size()));

        // 성과 추적 기록 (실패해도 분석 결과에 영향 없음)
        try {
            trackRecordService.recordAnalysis(response);
        } catch (Exception e) {
            log.warn("[Orchestrator] Track record failed: {}", e.getMessage());
        }

        return response;
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
        outPrices.putAll(prices);

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

    /** Flask API에서 시장 레짐(Market Gate) 상태를 가져온다. 실패 시 YELLOW 기본값. */
    private String fetchMarketGate() {
        try {
            String url = "http://localhost:5001/api/kr/market-gate";
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
