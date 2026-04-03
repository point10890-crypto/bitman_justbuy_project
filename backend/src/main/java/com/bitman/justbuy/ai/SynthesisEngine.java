package com.bitman.justbuy.ai;

import com.bitman.justbuy.ai.agent.ChatGptAgent;
import com.bitman.justbuy.dto.AgentResult;
import com.bitman.justbuy.dto.StockPick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class SynthesisEngine {

    private static final Logger log = LoggerFactory.getLogger(SynthesisEngine.class);

    private final ChatGptAgent chatGptAgent;

    static final String SYNTHESIS_PROMPT = "당신은 수석 AI 분석관입니다. 아래에 여러 AI 엔진이 독립적으로 수행한 주식 분석 결과가 있습니다.\n\n"
        + "**중요**: 사용자의 원래 질문을 잘 파악하세요!\n"
        + "- 사용자가 특정 종목(예: \"삼성전자 분석\")을 질문했다면 → 그 종목에 대한 심층 종합 분석을 작성하세요. 다른 종목 추천 금지!\n"
        + "- 사용자가 일반 추천(예: \"오늘 뭐 살까\", \"스윙 후보\")을 질문했다면 → 추천 종목 TOP을 정리하세요.\n\n"
        + "## 특정 종목 분석 모드 (사용자가 특정 종목을 물었을 때):\n\n"
        + "🎯 **종목 종합 분석**\n"
        + "📌 종목명 (6자리종목코드) — 현재가 약 XX,XXX원\n"
        + "  - 투자판단: 매수/관망/매도\n"
        + "  - AI 합의: X개 AI 전원/다수 동의\n"
        + "  - 목표가: XX,XXX원 / 손절가: XX,XXX원\n\n"
        + "🤝 **합의 사항** (다수 AI 동의)\n"
        + "⚔️ **의견 차이** (AI 간 갈등)\n\n"
        + "📊 **종합 시나리오** (확률 합=100%)\n"
        + "🟢 강세: XX% — 근거\n"
        + "🟡 기준: XX% — 근거\n"
        + "🔴 약세: XX% — 근거\n\n"
        + "💡 **최종 통합 판단** (5~10줄)\n"
        + "⚠️ 리스크 & 체크리스트\n\n"
        + "## 종목 추천 모드 (사용자가 일반 추천을 요청했을 때):\n\n"
        + "🎯 **추천 종목 TOP**\n"
        + "📌 종목명 (6자리종목코드) — 현재가 약 XX,XXX원\n"
        + "  - 투자판단: 매수/관망/매도\n"
        + "  - AI 합의: X개 AI 동의\n"
        + "  - 목표가: XX,XXX원 / 손절가: XX,XXX원\n"
        + "(각 AI가 추천한 종목을 종합하여 합의도가 높은 순으로 3~5개 정리. 반드시 종목코드 6자리 포함!)\n\n"
        + "🤝 **합의 사항** / ⚔️ **의견 차이** / 📊 **종합 시나리오** / 💡 **최종 통합 판단** / ⚠️ 리스크 & 체크리스트\n\n"
        + "📰 **뉴스·재료 종합**\n"
        + "  - 당일/금주 핵심 뉴스·공시와 추천 종목과의 연관성\n"
        + "  - 재료 강도: ★★★(강) / ★★(중) / ★(약)\n"
        + "  - 향후 1주 주요 이벤트 캘린더\n\n"
        + "면책: 정보 제공 목적이며 투자자문이 아닙니다.\n\n"
        + "**⚠️ 가격 규칙 (최우선, 위반 시 분석 무효)**:\n"
        + "1. 입력에 \"실시간 시세 참조\" 또는 \"실시간 시장 데이터\"가 포함되어 있으면, 해당 가격이 유일한 정확한 현재가입니다.\n"
        + "2. 개별 AI가 제시한 가격이 실시간 데이터와 다르면 무조건 실시간 데이터의 가격으로 교체하세요.\n"
        + "3. AI가 추정/학습한 과거 가격을 현재가로 사용하면 안 됩니다. 이는 거짓 정보입니다.\n"
        + "4. 실시간 데이터에 없는 종목은 \"현재가: 실시간 데이터 미제공\"으로 표시하세요.\n"
        + "5. \"📌 종목명 (종목코드) — 현재가 약 XX,XXX원\" 형식에서 XX,XXX는 반드시 실시간 데이터의 가격이어야 합니다.";

    public SynthesisEngine(ChatGptAgent chatGptAgent) {
        this.chatGptAgent = chatGptAgent;
    }

    public boolean isAvailable() {
        return chatGptAgent.isAvailable();
    }

    public AgentResult synthesizeWithResult(List<AgentResult> results, String query, String mode, String today) {
        return synthesizeWithResult(results, query, mode, today, "");
    }

    public AgentResult synthesizeWithResult(List<AgentResult> results, String query, String mode, String today, String consensusText) {
        return synthesizeWithResult(results, query, mode, today, consensusText, List.of(), Map.of());
    }

    /**
     * 실시간 검증 가격을 포함한 종합 합성.
     * stockPicks + realPrices를 받아 Synthesis 프롬프트에 "실시간 시세 참조" 블록을 주입.
     * R3 합성: gpt-4o (웹검색 없이 순수 추론)
     */
    public AgentResult synthesizeWithResult(List<AgentResult> results, String query, String mode, String today,
                                             String consensusText, List<StockPick> stockPicks, Map<String, String> realPrices) {
        String synthesisInput = results.stream()
            .map(r -> "=== " + r.agent().toUpperCase() + " 분석 (" + r.model() + ") ===\n" + r.content())
            .collect(Collectors.joining("\n\n---\n\n"));

        // ★ 실시간 검증 가격 참조 블록
        StringBuilder priceRef = new StringBuilder();
        if (!realPrices.isEmpty()) {
            priceRef.append("\n\n━━━ [실시간 시세 참조 — 네이버금융 실시간 시세] ━━━\n");
            priceRef.append("⚠️ 아래 가격만이 정확한 현재가입니다. AI 추정 가격은 무시하세요!\n");
            for (StockPick pick : stockPicks) {
                String price = realPrices.get(pick.code());
                if (price != null) {
                    priceRef.append("  ★ ").append(pick.name()).append("(").append(pick.code())
                        .append("): 현재가 ").append(price).append("원 (네이버 실시간)\n");
                }
            }
            priceRef.append("━━━ 위 가격을 반드시 현재가로 사용하세요! ━━━\n");
        }

        // ★ Grok X-검색 수급 데이터 우선 사용 지시
        String grokPriority = "";
        boolean hasGrok = results.stream().anyMatch(r -> "grok".equals(r.agent()) && "success".equals(r.status()));
        if (hasGrok) {
            grokPriority = "\n\n**⚠️ 데이터 우선순위 규칙**:\n"
                + "- Grok은 실시간 웹 검색과 X(트위터) 피드로 수급 데이터 및 뉴스를 수집합니다.\n"
                + "- 수급 데이터·뉴스·공시 등 시점이 중요한 정보는 Grok의 데이터를 최우선으로 채택하세요.\n"
                + "- ChatGPT(웹검색 포함)의 수급/뉴스 데이터가 Grok과 충돌하면 Grok을 우선합니다.\n"
                + "- 기술적 분석, 펀더멘탈 분석, 매크로 판단은 ChatGPT를 우선 참고하세요.\n"
                + "- Grok의 X(트위터) 센티먼트 분석은 역발상 관점으로 반드시 반영하세요.\n";
        }

        // ★ 수급분석 모드: 데이터 출처 규칙
        String supplyDataRule = "";
        if ("수급분석".equals(mode)) {
            supplyDataRule = "\n\n**🚨 수급분석 종합 시 필수 규칙**:\n"
                + "1. 외국인/기관 순매수·순매도 구체적 금액(억원)은 GROK 검색 결과에서만 채택하세요.\n"
                + "2. ChatGPT가 제시한 수급 금액 수치는 검색 출처가 있는 경우만 채택 (없으면 무시).\n"
                + "3. GROK 데이터가 없거나 실패한 경우, '실시간 수급 데이터 미수집'으로 명시하세요.\n"
                + "4. 종목 추천 시 수급 근거는 GROK만, 기술적/매크로 근거는 CHATGPT에서 가져오세요.\n"
                + "5. 시장이 -3% 이상 급락 중이면: 매수 추천보다 '관망' 또는 '매도/헤지'를 기본으로 하고, 매수는 매우 강한 근거가 있을 때만 조건부로 제시하세요.\n"
                + "6. 각 추천 종목의 수급 데이터 출처를 반드시 표기: [GROK 검색] 또는 [데이터 미확인]\n";
        }

        String agentRoleGuide = "\n\n**⚙️ 2-AGENT INTELLIGENCE SYSTEM — 종합 시 반드시 반영**:\n"
            + "| AI | 담당 Layer | 종합 시 채택 영역 |\n"
            + "|---|---|---|\n"
            + "| CHATGPT | L1 Macro + L2 Currency + L4 Technical + L6 Fundamental | 매크로, 환율, 기술적 분석, 재무분석, DART 공시, 적정가치 |\n"
            + "| GROK | L3 Capital Flow + L5 Derivatives + SNS/X | 수급 수치(실시간 검색), 파생 신호, X피드 센티먼트, 역발상 |\n\n"
            + "**⚠️ 종합 규칙 (EXECUTION)**:\n"
            + "1. 수급 수치(외국인/기관 금액)는 GROK 검색 결과만 채택. 출처 없는 수급 숫자는 무시\n"
            + "2. 목표가/손절가는 CHATGPT 기술적 레벨 + CHATGPT 적정가치 교차 검증\n"
            + "3. 시장 레짐은 CHATGPT(매크로+환율) 판단 기준\n"
            + "4. 뉴스/재료는 GROK 실시간 검색 + CHATGPT 웹검색 결합\n"
            + "5. 역발상·컨센서스 반대 관점은 GROK X피드 센티먼트 기반\n"
            + "6. 최종 판단에 Evidence Scoring 명시: Strong / Moderate / Weak\n"
            + "7. 시장 급락(-3%+) 시: 매수보다 리스크 관리(관망/매도/헤지) 우선\n"
            + "8. 두 AI가 동일 종목을 추천 → CONSENSUS (신뢰도 상향)\n"
            + "9. 한 AI만 추천 → 해당 AI의 전문 영역 기준으로 신뢰도 판단\n";

        String userMessage = "[오늘: " + today + "] [모드: " + mode + "] [사용자 원래 질문: " + query + "]\n\n"
            + "아래 " + results.size() + "개 AI 분석 결과를 종합하세요. 반드시 사용자의 원래 질문에 맞게 종합하세요!"
            + agentRoleGuide
            + grokPriority
            + supplyDataRule
            + priceRef
            + consensusText + "\n\n"
            + synthesisInput;

        // R3 합성: gpt-4o (웹검색 없는 순수 추론 모드)
        AgentResult synthesis = chatGptAgent.synthesize(SYNTHESIS_PROMPT, userMessage);
        log.info("[Synthesis] status={}, durationMs={}", synthesis.status(), synthesis.durationMs());
        return synthesis;
    }
}
