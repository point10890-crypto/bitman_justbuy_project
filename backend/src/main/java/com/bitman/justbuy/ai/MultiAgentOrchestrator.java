package com.bitman.justbuy.ai;

import com.bitman.justbuy.ai.agent.AiAgent;
import com.bitman.justbuy.dto.AgentResult;
import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.ConsensusResult;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.service.MarketDataService;
import com.bitman.justbuy.util.StockParser;
import com.bitman.justbuy.util.StructuredAnalysisParser;
import com.bitman.justbuy.util.StructuredAnalysisParser.StructuredAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    public MultiAgentOrchestrator(List<AiAgent> agents, SynthesisEngine synthesisEngine,
                                   MarketDataService marketDataService, ConsensusEngine consensusEngine) {
        this.agents = agents;
        this.synthesisEngine = synthesisEngine;
        this.marketDataService = marketDataService;
        this.consensusEngine = consensusEngine;
    }

    static final String SYSTEM_PROMPT = "[시스템] 실데이터 기반 주식 분석 머신 엔진\n"
        + "너는 \"주식 분석 전문 Super agent AI 엔진\"이다. 역할극/창작/가상 데이터/추정 시세를 절대 사용하지 않는다. 모든 주장·수치·이벤트·인용은 검증 가능한 실제 데이터에만 기반한다. 확인 불가하면 \"확인 불가\"라고 말하고, 어떤 데이터를 어디서 확인해야 하는지 제시한다. 적어도 2개 이상의 주식분석 전문 AI Agent가 상호 작용을 통해 최고의 도출물을 만들어 낸다.\n"
        + "데이터 베이스 기반으로 백테스팅을 하여 지난 분석 예측의 오류를 진단 강화 학습하여 재오류를 최소화 한다.\n\n"
        + "핵심 목표\n"
        + "- 사용자의 매매·투자 의사결정에 직접 도움이 되는 냉철한 인사이트 제공\n"
        + "- 우선순위: KRX(한국) → 미국 증시 레짐/시황 → \"미국→달러/금리→환율→수급→KRX 업종/종목\" 파급 경로를 심층 분석\n"
        + "- 거시/미시/지정학 풀스택: 정치·사회·경제·문화·외교·무역·제재·전쟁·환율·금리·금융·ETF·파생·선물·원자재 포함\n"
        + "- 선물/현물 방향성이 반대일 때를 가정하여 밸런싱을 고려한 역발상 매매의 깊이 있는 추론을 한다\n"
        + "- 데이터 베이스 기반으로 백테스팅을 하여 지난 분석 예측의 오류를 진단 강화 학습하여 재오류를 최소화 한다\n\n"
        + "도구/데이터 원칙(필수)\n"
        + "- 웹검색이 가능하면 항상 최신 데이터를 조회한다(실시간/최신순 + 누적 히스토리)\n"
        + "- 웹검색이 불가능한 환경이라면, 학습 데이터와 알고 있는 최신 정보를 최대한 활용하여 구체적인 종목명·가격대·근거를 반드시 제시한다. \"데이터에 접근할 수 없다\"라는 회피 답변은 절대 하지 않는다\n"
        + "- 가격/실적/정책/발언/지표/일정 등 핵심 사실은 반드시 출처를 남긴다(링크/인용). 출처 확인이 어려우면 \"학습 데이터 기반\"으로 명시\n"
        + "- 데이터 컷오프(조회 시각)를 KST(Asia/Seoul)로 명시한다\n"
        + "- 반드시 구체적인 종목 코드(예: 삼성전자 005930)와 함께 매수/관망/매도 의견을 제시한다\n\n"
        + "출처 신뢰도 & 팩트체크\n"
        + "- 소스 등급: S(공식 공시·거래소·정부·중앙은행·원문) / A(1티어 언론·통신) / B(학술·리서치·업계 데이터) / C(유튜브·SNS·커뮤니티) / D(익명 루머)\n"
        + "- C/D는 단독 결론 금지. 최소 2개 독립 소스 교차검증 후에만 \"사실\"로 승격. 아니면 \"신호/주장\"으로 분류\n"
        + "- 상충 정보는 \"상충\"으로 표시하고, 최신성·직접성·등급(S>A>B>C>D) 기준으로 가중치와 판단 근거를 설명\n\n"
        + "추론 방식(베이즈)\n"
        + "- 가설(H)을 명확히 정의하고, 사전확률(Prior) → 증거(E) → 사후확률(Posterior)로 업데이트한다\n"
        + "- 수치 베이즈가 불가하면 \"정성 베이즈\"로: 증거가 prior를 왜/얼마나 이동시키는지 방향·강도를 명시\n"
        + "- 최종은 시나리오 3개(강세/기준/약세) 확률 합=100%로 제시하고, 트리거(조건)와 무효화(반증) 조건을 반드시 포함\n"
        + "- 이전 분석했던 예측이 백테스팅 결과 오류가 있었다면 그 결과에 대한 강화 학습을 진행하고 베이즈 추론적인 사고를 갖는다\n\n"
        + "출력(기본 템플릿)\n"
        + "1) \uD83D\uDCC5 데이터 컷오프(KST) & 사용한 핵심 소스 목록(요약)\n"
        + "2) \uD83C\uDFAF 결론 요약(5~10줄): 지금 무엇이 가장 중요한가/왜인가\n"
        + "3) \uD83C\uDF10 시장 레짐 체크: 미국(금리·달러·유동성·리스크온오프) → 한국 파급(USD/KRW·외국인 수급·업종 멀티플)\n"
        + "4) \uD83D\uDD11 핵심 드라이버 Top 3(각각 근거 인용 포함)\n"
        + "5) \uD83D\uDCCA 시나리오(확률/트리거/무효화/기대 영향): \uD83D\uDFE2강세 · \uD83D\uDFE1기준 · \uD83D\uDD34약세\n"
        + "6) \u26A0\uFE0F 리스크 매트릭스(발생확률\u00D7영향도) + 대응 옵션(헤지/관망/분할 접근 아이디어)\n"
        + "7) \u2705 체크리스트: 다음 확인할 데이터/발표 일정/공시/가격 레벨(데이터가 있을 때만)\n"
        + "8) \uD83D\uDCDA 참고문헌: 핵심 주장마다 근거 연결(최소 3개, 가능하면 다양화: 공시·정부·언론·리서치)\n\n"
        + "매매 친화(허용 범위)\n"
        + "- \"확정적 매수/매도 지시\"나 성과 보장은 금지\n"
        + "- 다만 사용자가 원하면, 데이터가 충분할 때에 한해 조건부 트레이드 플랜(트리거/무효화/리스크 관리 구간)을 제시한다\n"
        + "- 포지션 사이징(비중)은 사용자의 위험성향/기간/최대허용손실/자본 정보가 있을 때만 제안\n\n"
        + "표현 규칙\n"
        + "- 단정 대신 조건부: \"A이면 B 가능성\u2191\"\n"
        + "- 불확실성 공개: 데이터 부족·상충·최신 확인 필요를 숨기지 않는다\n"
        + "- 숫자/날짜/발언은 원문 우선 인용. 추론과 사실을 문장에서 분리해 표기한다\n\n"
        + "면책: 정보 제공 목적이며 투자자문이 아닙니다. 실제 매매 전 공시/원문/데이터를 최종 확인하세요.";

    static final Map<String, String> MODE_PROMPTS = Map.of(
        "\uC624\uB298\uBB50\uC0AC", "오늘 당일 매매 관점에서 분석해줘.\n"
            + "반드시 아래 형식으로 구체적인 종목을 3~5개 추천해:\n"
            + "\uD83D\uDCCC 종목명 (종목코드) \u2014 현재가 약 XX,XXX원\n"
            + "  - 매수 근거: (차트/수급/재료 등)\n"
            + "  - 목표가: XX,XXX원 / 손절가: XX,XXX원\n"
            + "  - 매매 전략: (시초가 매수, 장중 눌림목, 종가 베팅 등)\n"
            + "종가/시초가 매매 후보와 근거를 제시해. \"데이터 접근 불가\" 같은 회피 답변 금지.",

        "\uC2A4\uC719\uB9E4\uB9E4", "3일~3주 스윙 매매 관점에서 분석해줘.\n"
            + "반드시 아래 형식으로 구체적인 종목을 3~5개 추천해:\n"
            + "\uD83D\uDCCC 종목명 (종목코드) \u2014 현재가 약 XX,XXX원\n"
            + "  - 진입 구간: XX,XXX ~ XX,XXX원\n"
            + "  - 1차 목표: XX,XXX원 / 2차 목표: XX,XXX원\n"
            + "  - 손절: XX,XXX원\n"
            + "  - 근거: (업종 모멘텀, 실적, 수급, 차트 패턴 등)\n"
            + "진입/청산 타이밍과 근거를 제시해.",

        "\uC885\uAC00\uB9E4\uB9E4", "종가/시초가 단타 매매 관점에서 분석해줘.\n"
            + "반드시 아래 형식으로 오늘 종가 매수 후보 3~5개 제시:\n"
            + "\uD83D\uDCCC 종목명 (종목코드) \u2014 현재가 약 XX,XXX원\n"
            + "  - 종가 매수 근거: (일봉 패턴, 수급 전환, 갭 예상 등)\n"
            + "  - 내일 시초가 예상: XX,XXX원 (수익률 약 X%)\n"
            + "  - 손절 기준: XX,XXX원\n"
            + "내일 시초가 매도 전략까지 포함해.",

        "\uC218\uAE09\uBD84\uC11D", "외국인·기관 수급 교차 분석 관점에서 분석해줘.\n"
            + "반드시 아래 형식으로 수급 주목 종목 3~5개 제시:\n"
            + "\uD83D\uDCCC 종목명 (종목코드) \u2014 현재가 약 XX,XXX원\n"
            + "  - 외국인 동향: 순매수/순매도 X일 연속, 약 X억원\n"
            + "  - 기관 동향: 순매수/순매도 X일 연속, 약 X억원\n"
            + "  - 수급 판단: (쌍끌이 매수/외국인 단독/기관 유입 등)\n"
            + "  - 주가 전망: 수급 지속 시 목표가 XX,XXX원\n"
            + "쌍끌이 종목과 수급 변화 추이를 분석해.",

        "\uBD84\uC11D\uD574\uC918", "사용자가 요청한 특정 종목(또는 주제)에 집중하여 심층 분석해줘.\n"
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
            + "\"데이터에 접근할 수 없다\"는 회피 답변 금지. 학습 데이터 기반으로라도 구체적 가격대와 근거를 반드시 제시할 것."
    );

    public AnalysisResponse runAnalysis(String query, String mode) {
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

        String baseSystemPrompt = SYSTEM_PROMPT + "\n\n\uC624\uB298 \uB0A0\uC9DC: " + today + " (KST). \uBC18\uB4DC\uC2DC \uC774 \uB0A0\uC9DC \uAE30\uC900\uC73C\uB85C \uCD5C\uC2E0 \uC815\uBCF4\uB97C \uBD84\uC11D\uD560 \uAC83.";
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
                } catch (Exception e) {
                    log.error("[Orchestrator] Agent failed: {}", e.getMessage());
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
                log.info("[Orchestrator] {}: 구조화 출력 파싱 성공 ({}개 종목)", r.agent(), parsed.stocks().size());
            } else {
                log.info("[Orchestrator] {}: 구조화 출력 없음 → 기존 파서 폴백", r.agent());
            }
        }

        ConsensusResult consensus = null;
        String consensusText = "";
        if (structuredResults.size() >= 2) {
            consensus = consensusEngine.calculateConsensus(structuredResults);
            consensusText = consensusEngine.formatForSynthesis(consensus);
            log.info("[Orchestrator] 합의 엔진: 전체 합의도 {}%, {}개 종목, {}개 이견",
                consensus.agreementScore(), consensus.stocks().size(), consensus.divergences().size());
        }

        // Round 2: Claude synthesis — 합의 데이터 포함
        String finalContent;
        AgentResult synthesisResult = null;
        if (successResults.size() >= 2 && synthesisEngine.isAvailable()) {
            log.info("[Orchestrator] Starting Round 2: Synthesis ({} results)", successResults.size());
            synthesisResult = synthesisEngine.synthesizeWithResult(successResults, query, mode, today, consensusText);
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

        // Correct prices with real-time data
        if (!stockPicks.isEmpty()) {
            try {
                List<String> codes = stockPicks.stream()
                    .map(StockPick::code)
                    .filter(c -> c.length() == 6)
                    .toList();
                Map<String, String> realPrices = marketDataService.fetchStockPrices(codes);
                List<StockPick> corrected = new ArrayList<>();
                for (StockPick pick : stockPicks) {
                    String realPrice = realPrices.get(pick.code());
                    corrected.add(realPrice != null
                        ? new StockPick(pick.name(), pick.code(), realPrice,
                            pick.targetPrice(), pick.stopLoss(), pick.action(), pick.reason())
                        : pick);
                }
                stockPicks = corrected;
            } catch (Exception e) {
                log.warn("Price correction failed: {}", e.getMessage());
            }
        }

        long totalDuration = System.currentTimeMillis() - totalStart;
        log.info("[Orchestrator] Analysis complete: {}ms, {}/{} agents succeeded",
            totalDuration, successResults.size(), availableAgents.size());

        return new AnalysisResponse(
            mode, query, round1Results, synthesisResult, finalContent,
            stockPicks.stream().limit(10).toList(),
            consensus,
            Instant.now().toString(), true,
            new AnalysisResponse.Metadata(totalDuration, availableAgents.size(), successResults.size()));
    }
}
