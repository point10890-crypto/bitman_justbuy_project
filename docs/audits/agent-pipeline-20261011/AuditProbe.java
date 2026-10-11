package com.bitman.justbuy.service;

import com.bitman.justbuy.ai.agent.*;
import com.bitman.justbuy.config.AiProperties;
import com.bitman.justbuy.dto.AgentResult;
import com.bitman.justbuy.dto.condition.EvidencePacket;
import com.bitman.justbuy.util.StructuredAnalysisParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import java.io.IOException;
import java.util.*;

public final class AuditProbe {
  static final ObjectMapper MAPPER = new ObjectMapper();
  static final AiProperties PROPS = new AiProperties("fixture-only", "fixture-only", "fixture-only", "fixture-only", "", "");
  static final class ResponseStub extends RestTemplate {
    String json;
    String url;
    String authorization;
    int calls;
    ResponseStub(String json) { this.json = json; }
    @Override public <T> ResponseEntity<T> exchange(String url, HttpMethod method, HttpEntity<?> entity, Class<T> type, Object... variables) {
      this.url = url;
      this.authorization = entity.getHeaders().getFirst("Authorization");
      calls++;
      return ResponseEntity.ok(type.cast(json));
    }
  }
  static final class ConfigStub extends RuntimeAiConfigService {
    String host = "https://api.deepseek.com";
    ConfigStub() { super(PROPS, MAPPER, "fixture-only-never-read"); }
    @Override public synchronized DeepSeekConfig getDeepSeekConfig() {
      return new DeepSeekConfig("fixture-only", host, "deepseek-v4-flash", "fixture", null);
    }
  }
  static String responses(String status, String... parts) throws Exception {
    List<Map<String,Object>> content = new ArrayList<>();
    for (String part : parts) content.add(Map.of("type", "output_text", "text", part));
    return MAPPER.writeValueAsString(Map.of("status", status, "model", "fixture-model", "output", List.of(Map.of("type", "message", "content", content)), "usage", Map.of("input_tokens", 20, "output_tokens", 30)));
  }
  static String completion(String reason, String content) throws Exception {
    Map<String,Object> choice = new LinkedHashMap<>();
    choice.put("message", Map.of("content", content));
    if (reason != null) choice.put("finish_reason", reason);
    return MAPPER.writeValueAsString(Map.of("model", "fixture-model", "choices", List.of(choice), "usage", Map.of("prompt_tokens", 20, "completion_tokens", 30)));
  }
  static void output(String name, String value) { System.out.println(name + "=" + value); }
  public static void main(String[] args) throws Exception {
    ResponseStub grokHttp = new ResponseStub(responses("incomplete", "partial"));
    AgentResult grokIncomplete = new GrokAgent(PROPS, grokHttp, MAPPER).analyze("system", "user");
    output("grok_incomplete", grokIncomplete.status() + ", content=" + grokIncomplete.content());
    grokHttp.json = "{}";
    AgentResult grokEmpty = new GrokAgent(PROPS, grokHttp, MAPPER).analyze("system", "user");
    output("grok_empty", grokEmpty.status() + ", blank=" + grokEmpty.content().isBlank());
    String structured = "```json:analysis\n{\"stocks\":[{\"name\":\"fixture\",\"code\":\"005930\"}]}\n```";
    grokHttp.json = responses("completed", "narrative", structured);
    AgentResult grokParts = new GrokAgent(PROPS, grokHttp, MAPPER).analyze("system", "user");
    output("grok_multiple_parts", "keepsSecond=" + grokParts.content().contains("stocks") + ", parsed=" + (StructuredAnalysisParser.parse(grokParts.content()) != null));
    grokHttp.json = "{\"status\":\"completed\",\"citations\":[\"https://example.org/fixture-source\"],\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"analysis\",\"annotations\":[{\"type\":\"url_citation\",\"url\":\"https://example.org/fixture-source\"}]}]}]}";
    AgentResult grokCitations = new GrokAgent(PROPS, grokHttp, MAPPER).analyze("system", "user");
    output("grok_official_citations", "retained=" + grokCitations.content().contains("example.org"));

    ResponseStub geminiHttp = new ResponseStub("{\"modelVersion\":\"gemini-fixture-version\",\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{\"parts\":[{\"text\":\"partial\"}]}}],\"usageMetadata\":{\"promptTokenCount\":20,\"candidatesTokenCount\":30,\"thoughtsTokenCount\":40,\"totalTokenCount\":90}}");
    AgentResult geminiIncomplete = new GeminiAgent(PROPS, geminiHttp, MAPPER).analyze("system", "user");
    output("gemini_max_tokens", geminiIncomplete.status() + ", content=" + geminiIncomplete.content());
    output("gemini_metadata", "model=" + geminiIncomplete.model() + ", measuredTotal=" + (geminiIncomplete.inputTokens() + geminiIncomplete.outputTokens()) + ", providerTotal=90");
    geminiHttp.json = "{\"candidates\":[{\"finishReason\":\"SAFETY\",\"groundingMetadata\":{\"groundingChunks\":[{\"web\":{\"uri\":\"https://example.org/fixture-source\"}}]}}]}";
    AgentResult geminiNoText = new GeminiAgent(PROPS, geminiHttp, MAPPER).analyze("system", "user");
    output("gemini_sources_without_answer", geminiNoText.status() + ", blank=" + geminiNoText.content().isBlank());
    RestTemplate offlineFailure = new RestTemplate((uri, method) -> { throw new IOException("fixture-only I/O failure"); });
    AgentResult sanitized = new GeminiAgent(PROPS, offlineFailure, MAPPER).analyze("system", "user");
    output("gemini_real_spring_io_error", "status=" + sanitized.status() + ", leaksQueryKey=" + sanitized.error().contains("key=fixture-only"));

    ConfigStub config = new ConfigStub();
    ResponseStub deepseekHttp = new ResponseStub(completion(null, "partial"));
    DeepSeekAgent deepseek = new DeepSeekAgent(PROPS, config, deepseekHttp, MAPPER);
    output("deepseek_no_finish_reason", deepseek.analyze("system", "user").status());
    deepseekHttp.json = completion("length", "partial");
    AgentResult deepseekLength = deepseek.analyze("system", "user");
    output("deepseek_truncated_usage", "status=" + deepseekLength.status() + ", measuredTokens=" + (deepseekLength.inputTokens() + deepseekLength.outputTokens()) + ", providerTokens=50, candidateLength=" + deepseekLength.content().length());
    config.host = "http://unrelated-host.invalid";
    deepseekHttp.json = completion("stop", "ok");
    deepseek.analyze("system", "user");
    output("deepseek_host_boundary", "acceptsArbitraryHttpHost=" + deepseekHttp.url.startsWith(config.host) + ", sendsProviderCredential=" + "Bearer fixture-only".equals(deepseekHttp.authorization));

    ResponseStub synthesisHttp = new ResponseStub(completion("length", "partial"));
    output("chatgpt_synthesis_length", new ChatGptAgent(PROPS, synthesisHttp, MAPPER).synthesize("system", "user").status());
    ResponseStub chatgptAnalysisHttp = new ResponseStub(responses("incomplete", "partial"));
    AgentResult openaiIncomplete = new ChatGptAgent(PROPS, chatgptAnalysisHttp, MAPPER).analyze("system", "user");
    output("chatgpt_analysis_incomplete", openaiIncomplete.status());

    DeepSeekConditionVerifier verifier = new DeepSeekConditionVerifier(null, MAPPER);
    EvidencePacket packet = new EvidencePacket("LEADERS", "FLOW_LEADER", "fixture-date", "005930", "fixture", "100", "1", 80, List.of("deterministic fact"), Map.of());
    var missing = verifier.parse("{\"signals\":[{\"stockCode\":\"005930\",\"verdict\":\"VALID\",\"aiScore\":99}]}", List.of(packet)).get("005930");
    output("verifier_missing_required", "accepted=" + (missing != null) + ", riskCount=" + missing.riskFlags().size() + ", invalidationEmpty=" + missing.invalidation().isEmpty() + ", aiScore=" + missing.aiScore());
    var unknown = verifier.parse("{\"signals\":[{\"stockCode\":\"999999\",\"verdict\":\"VALID\",\"aiScore\":99}]}", List.of(packet));
    output("verifier_outside_candidate", "retained=" + unknown.containsKey("999999"));
    var invalidVerdict = verifier.parse("{\"signals\":[{\"stockCode\":\"005930\",\"verdict\":\"INVALID_ENUM\",\"aiScore\":99}]}", List.of(packet)).get("005930");
    output("verifier_invalid_enum", "accepted=" + (invalidVerdict != null) + ", rejected=" + invalidVerdict.rejected());
    var duplicate = verifier.parse("{\"signals\":[{\"stockCode\":\"005930\",\"verdict\":\"REJECT\"},{\"stockCode\":\"005930\",\"verdict\":\"VALID\",\"aiScore\":99}]}", List.of(packet)).get("005930");
    output("verifier_duplicate", "final=" + duplicate.verdict());

    var malformed = StructuredAnalysisParser.parse("```json:analysis\n{\"stocks\":[{\"name\":\"fixture\",\"code\":\"ABCDEF\",\"confidence\":\"NaN\",\"scenario\":{\"bull\":{\"probability\":0},\"base\":{\"probability\":0},\"bear\":{\"probability\":0}}}]}\n```");
    output("structured_schema", "nonNumericTicker=" + malformed.stocks().getFirst().code() + ", confidence=" + malformed.stocks().getFirst().confidence() + ", scenarioProbabilitySum=" + (malformed.stocks().getFirst().scenario().bull().probability() + malformed.stocks().getFirst().scenario().base().probability() + malformed.stocks().getFirst().scenario().bear().probability()) + ", reasons=" + malformed.stocks().getFirst().reasons().size());
    output("network", "0 network requests; all exchanges are response stubs or a request factory that throws before connection");
  }
}
