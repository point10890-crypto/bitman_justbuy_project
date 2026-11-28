import httpx
import pytest

from condition_engine import agent
from condition_engine.agent import GeminiProvider


def test_gemini_sends_a_schema_without_deprecated_sampling_or_key_in_url(monkeypatch):
    def post(url, *, timeout, headers, json):
        assert url == "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"
        assert headers["x-goog-api-key"] == "fixture-key"
        assert timeout > 0
        config = json["generationConfig"]
        assert config["responseMimeType"] == "application/json"
        assert set(config["responseJsonSchema"]["required"]) == {"resolutions", "unsupported"}
        assert {"temperature", "topP", "topK"}.isdisjoint(config)
        return httpx.Response(200, json={"candidates": [{"finishReason": "STOP", "content": {"parts": [
            {"thought": True, "text": "provider thought"}, {"text": '{"resolutions":[],'}, {"text": '"unsupported":[]}'},
        ]}}]})

    monkeypatch.setattr(httpx, "post", post)
    assert GeminiProvider("fixture-key").complete_json("system", "user") == '{"resolutions":[],"unsupported":[]}'


def test_gemini_provider_surfaces_only_whitelisted_credential_failure_details(monkeypatch):
    response = httpx.Response(400, request=httpx.Request("POST", "https://generativelanguage.googleapis.com/"),
        json={"error": {"message": "private provider payload", "status": "INVALID_ARGUMENT",
                        "details": [{"reason": "API_KEY_INVALID", "metadata": {"key": "do-not-return"}}]}})
    monkeypatch.setattr(httpx, "post", lambda *a, **kw: response)

    with pytest.raises(RuntimeError, match=r"Gemini HTTP 400 · INVALID_ARGUMENT · API_KEY_INVALID") as exc:
        GeminiProvider("fixture-key").complete_json("system", "user")
    assert "private provider payload" not in str(exc.value) and "do-not-return" not in str(exc.value)


def test_gemini_provider_rejects_incomplete_answers(monkeypatch):
    response = httpx.Response(200, request=httpx.Request("POST", "https://generativelanguage.googleapis.com/"),
        json={"candidates": [{"finishReason": "MAX_TOKENS", "content": {"parts": [
            {"text": '{"resolutions":[],"unsupported":[]}'},
        ]}}]})
    monkeypatch.setattr(httpx, "post", lambda *a, **kw: response)

    with pytest.raises(RuntimeError, match="Gemini response incomplete"):
        GeminiProvider("fixture-key").complete_json("system", "user")


def test_translation_prefers_gemini_with_openai_as_available_fallback(monkeypatch):
    monkeypatch.setattr(agent, "_load_env", lambda: None)
    monkeypatch.setenv("GOOGLE_API_KEY", "fixture-gemini")
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    monkeypatch.setenv("OPENAI_API_KEY", "fixture-openai")

    assert agent.build_translator("auto").chain == "gemini→openai→rules"
    assert agent.build_translator("gemini").chain == "gemini→rules"
    assert agent.build_translator("openai").chain == "openai→rules"
