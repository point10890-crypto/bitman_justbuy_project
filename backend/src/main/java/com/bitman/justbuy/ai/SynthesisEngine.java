package com.bitman.justbuy.ai;

import com.bitman.justbuy.ai.agent.ClaudeAgent;
import com.bitman.justbuy.dto.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class SynthesisEngine {

    private static final Logger log = LoggerFactory.getLogger(SynthesisEngine.class);

    private final ClaudeAgent claudeAgent;

    static final String SYNTHESIS_PROMPT = "\uB2F9\uC2E0\uC740 \uC218\uC11D AI \uBD84\uC11D\uAD00\uC785\uB2C8\uB2E4. \uC544\uB798\uC5D0 \uC5EC\uB7EC AI \uC5D4\uC9C4\uC774 \uB3C5\uB9BD\uC801\uC73C\uB85C \uC218\uD589\uD55C \uC8FC\uC2DD \uBD84\uC11D \uACB0\uACFC\uAC00 \uC788\uC2B5\uB2C8\uB2E4.\n\n"
        + "**\uC911\uC694**: \uC0AC\uC6A9\uC790\uC758 \uC6D0\uB798 \uC9C8\uBB38\uC744 \uC798 \uD30C\uC545\uD558\uC138\uC694!\n"
        + "- \uC0AC\uC6A9\uC790\uAC00 \uD2B9\uC815 \uC885\uBAA9(\uC608: \"\uC0BC\uC131\uC804\uC790 \uBD84\uC11D\")\uC744 \uC9C8\uBB38\uD588\uB2E4\uBA74 \u2192 \uADF8 \uC885\uBAA9\uC5D0 \uB300\uD55C \uC2EC\uCE35 \uC885\uD569 \uBD84\uC11D\uC744 \uC791\uC131\uD558\uC138\uC694. \uB2E4\uB978 \uC885\uBAA9 \uCD94\uCC9C \uAE08\uC9C0!\n"
        + "- \uC0AC\uC6A9\uC790\uAC00 \uC77C\uBC18 \uCD94\uCC9C(\uC608: \"\uC624\uB298 \uBB58 \uC0B4\uAE4C\", \"\uC2A4\uC719 \uD6C4\uBCF4\")\uC744 \uC9C8\uBB38\uD588\uB2E4\uBA74 \u2192 \uCD94\uCC9C \uC885\uBAA9 TOP\uC744 \uC815\uB9AC\uD558\uC138\uC694.\n\n"
        + "## \uD2B9\uC815 \uC885\uBAA9 \uBD84\uC11D \uBAA8\uB4DC (\uC0AC\uC6A9\uC790\uAC00 \uD2B9\uC815 \uC885\uBAA9\uC744 \uBB3C\uC5C8\uC744 \uB54C):\n\n"
        + "\uD83C\uDFAF **\uC885\uBAA9 \uC885\uD569 \uBD84\uC11D**\n"
        + "\uD83D\uDCCC \uC885\uBAA9\uBA85 (6\uC790\uB9AC\uC885\uBAA9\uCF54\uB4DC) \u2014 \uD604\uC7AC\uAC00 \uC57D XX,XXX\uC6D0\n"
        + "  - \uD22C\uC790\uD310\uB2E8: \uB9E4\uC218/\uAD00\uB9DD/\uB9E4\uB3C4\n"
        + "  - AI \uD569\uC758: X\uAC1C AI \uC804\uC6D0/\uB2E4\uC218 \uB3D9\uC758\n"
        + "  - \uBAA9\uD45C\uAC00: XX,XXX\uC6D0 / \uC190\uC808\uAC00: XX,XXX\uC6D0\n\n"
        + "\uD83E\uDD1D **\uD569\uC758 \uC0AC\uD56D** (\uB2E4\uC218 AI \uB3D9\uC758)\n"
        + "\u2694\uFE0F **\uC758\uACAC \uCC28\uC774** (AI \uAC04 \uAC08\uB4F1)\n\n"
        + "\uD83D\uDCCA **\uC885\uD569 \uC2DC\uB098\uB9AC\uC624** (\uD655\uB960 \uD569=100%)\n"
        + "\uD83D\uDFE2 \uAC15\uC138: XX% \u2014 \uADFC\uAC70\n"
        + "\uD83D\uDFE1 \uAE30\uC900: XX% \u2014 \uADFC\uAC70\n"
        + "\uD83D\uDD34 \uC57D\uC138: XX% \u2014 \uADFC\uAC70\n\n"
        + "\uD83D\uDCA1 **\uCD5C\uC885 \uD1B5\uD569 \uD310\uB2E8** (5~10\uC904)\n"
        + "\u26A0\uFE0F \uB9AC\uC2A4\uD06C & \uCCB4\uD06C\uB9AC\uC2A4\uD2B8\n\n"
        + "## \uC885\uBAA9 \uCD94\uCC9C \uBAA8\uB4DC (\uC0AC\uC6A9\uC790\uAC00 \uC77C\uBC18 \uCD94\uCC9C\uC744 \uC694\uCCAD\uD588\uC744 \uB54C):\n\n"
        + "\uD83C\uDFAF **\uCD94\uCC9C \uC885\uBAA9 TOP**\n"
        + "\uD83D\uDCCC \uC885\uBAA9\uBA85 (6\uC790\uB9AC\uC885\uBAA9\uCF54\uB4DC) \u2014 \uD604\uC7AC\uAC00 \uC57D XX,XXX\uC6D0\n"
        + "  - \uD22C\uC790\uD310\uB2E8: \uB9E4\uC218/\uAD00\uB9DD/\uB9E4\uB3C4\n"
        + "  - AI \uD569\uC758: X\uAC1C AI \uB3D9\uC758\n"
        + "  - \uBAA9\uD45C\uAC00: XX,XXX\uC6D0 / \uC190\uC808\uAC00: XX,XXX\uC6D0\n"
        + "(\uAC01 AI\uAC00 \uCD94\uCC9C\uD55C \uC885\uBAA9\uC744 \uC885\uD569\uD558\uC5EC \uD569\uC758\uB3C4\uAC00 \uB192\uC740 \uC21C\uC73C\uB85C 3~5\uAC1C \uC815\uB9AC. \uBC18\uB4DC\uC2DC \uC885\uBAA9\uCF54\uB4DC 6\uC790\uB9AC \uC22B\uC790 \uD3EC\uD568!)\n\n"
        + "\uD83E\uDD1D **\uD569\uC758 \uC0AC\uD56D** / \u2694\uFE0F **\uC758\uACAC \uCC28\uC774** / \uD83D\uDCCA **\uC885\uD569 \uC2DC\uB098\uB9AC\uC624** / \uD83D\uDCA1 **\uCD5C\uC885 \uD1B5\uD569 \uD310\uB2E8** / \u26A0\uFE0F \uB9AC\uC2A4\uD06C & \uCCB4\uD06C\uB9AC\uC2A4\uD2B8\n\n"
        + "\uBA74\uCC45: \uC815\uBCF4 \uC81C\uACF5 \uBAA9\uC801\uC774\uBA70 \uD22C\uC790\uC790\uBB38\uC774 \uC544\uB2D9\uB2C8\uB2E4.";

    public SynthesisEngine(ClaudeAgent claudeAgent) {
        this.claudeAgent = claudeAgent;
    }

    public boolean isAvailable() {
        return claudeAgent.isAvailable();
    }

    public AgentResult synthesizeWithResult(List<AgentResult> results, String query, String mode, String today) {
        String synthesisInput = results.stream()
            .map(r -> "=== " + r.agent().toUpperCase() + " \uBD84\uC11D (" + r.model() + ") ===\n" + r.content())
            .collect(Collectors.joining("\n\n---\n\n"));

        String userMessage = "[\uC624\uB298: " + today + "] [\uBAA8\uB4DC: " + mode + "] [\uC0AC\uC6A9\uC790 \uC6D0\uB798 \uC9C8\uBB38: " + query + "]\n\n"
            + "\uC544\uB798 " + results.size() + "\uAC1C AI \uBD84\uC11D \uACB0\uACFC\uB97C \uC885\uD569\uD558\uC138\uC694. \uBC18\uB4DC\uC2DC \uC0AC\uC6A9\uC790\uC758 \uC6D0\uB798 \uC9C8\uBB38\uC5D0 \uB9DE\uAC8C \uC885\uD569\uD558\uC138\uC694!\n\n"
            + synthesisInput;

        AgentResult synthesis = claudeAgent.analyze(SYNTHESIS_PROMPT, userMessage);
        log.info("[Synthesis] status={}, durationMs={}", synthesis.status(), synthesis.durationMs());
        return synthesis;
    }
}
