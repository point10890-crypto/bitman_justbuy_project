package com.bitman.justbuy.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AgentRolesTest {

    // ── 2-에이전트 전문화 존재 확인 ─────────────────────────
    @Test
    void chatgpt_hasSpecialization() {
        String spec = AgentRoles.getSpecialization("chatgpt");
        assertThat(spec).isNotBlank();
    }

    @Test
    void grok_hasSpecialization() {
        String spec = AgentRoles.getSpecialization("grok");
        assertThat(spec).isNotBlank();
    }

    // ── 삭제된 에이전트는 빈 문자열 ─────────────────────────
    @Test
    void claude_hasNoSpecialization() {
        assertThat(AgentRoles.getSpecialization("claude")).isEmpty();
    }

    @Test
    void gemini_hasNoSpecialization() {
        assertThat(AgentRoles.getSpecialization("gemini")).isEmpty();
    }

    @Test
    void perplexity_hasNoSpecialization() {
        assertThat(AgentRoles.getSpecialization("perplexity")).isEmpty();
    }

    @Test
    void unknown_hasNoSpecialization() {
        assertThat(AgentRoles.getSpecialization("unknown_agent")).isEmpty();
    }

    // ── ChatGPT 레이어 배분 확인 ──────────────────────────
    @Test
    void chatgpt_coversLayer1Macro() {
        assertThat(AgentRoles.getSpecialization("chatgpt")).contains("Layer 1");
    }

    @Test
    void chatgpt_coversLayer2Currency() {
        assertThat(AgentRoles.getSpecialization("chatgpt")).contains("Layer 2");
    }

    @Test
    void chatgpt_coversLayer4Technical() {
        assertThat(AgentRoles.getSpecialization("chatgpt")).contains("Layer 4");
    }

    @Test
    void chatgpt_coversLayer6Fundamental() {
        assertThat(AgentRoles.getSpecialization("chatgpt")).contains("Layer 6");
    }

    // ── Grok 레이어 배분 확인 ────────────────────────────
    @Test
    void grok_coversLayer3CapitalFlow() {
        assertThat(AgentRoles.getSpecialization("grok")).contains("Layer 3");
    }

    @Test
    void grok_coversLayer5Derivatives() {
        assertThat(AgentRoles.getSpecialization("grok")).contains("Layer 5");
    }

    @Test
    void grok_mentionsXSearch() {
        assertThat(AgentRoles.getSpecialization("grok")).containsAnyOf("x_search", "X(트위터)", "X 검색");
    }

    @Test
    void grok_mentionsWebSearch() {
        assertThat(AgentRoles.getSpecialization("grok")).containsAnyOf("web_search", "웹 검색", "검색 툴");
    }

    // ── 구조화 출력 지시 포함 ────────────────────────────
    @Test
    void structuredOutputInstruction_isNotBlank() {
        assertThat(AgentRoles.STRUCTURED_OUTPUT_INSTRUCTION).isNotBlank();
    }

    @Test
    void structuredOutputInstruction_containsJsonBlock() {
        assertThat(AgentRoles.STRUCTURED_OUTPUT_INSTRUCTION).contains("json:analysis");
    }

    @Test
    void structuredOutputInstruction_containsRequiredFields() {
        String instr = AgentRoles.STRUCTURED_OUTPUT_INSTRUCTION;
        assertThat(instr).contains("confidence");
        assertThat(instr).contains("targetPrice");
        assertThat(instr).contains("stopLoss");
        assertThat(instr).contains("scenario");
    }

    // ── getFullSuffix ───────────────────────────────────
    @Test
    void fullSuffix_containsBothSpecAndInstruction() {
        String suffix = AgentRoles.getFullSuffix("chatgpt");
        assertThat(suffix).contains("Layer 1");
        assertThat(suffix).contains("json:analysis");
    }

    @Test
    void fullSuffix_forUnknown_containsOnlyInstruction() {
        String suffix = AgentRoles.getFullSuffix("unknown");
        assertThat(suffix).doesNotContain("Layer 1");
        assertThat(suffix).contains("json:analysis");
    }
}
