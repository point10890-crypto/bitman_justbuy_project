import json

import pytest
from fastapi.testclient import TestClient

from condition_engine import dsl
from condition_engine.agent import LlmTranslator, RuleTranslator
from condition_engine.api import EngineService, create_app
from condition_engine.explain import numbers_are_grounded
from condition_engine.ledger import Ledger


def leaves(strategy):
    w = strategy["when"]
    return w["all"] if "all" in w else [w]


# ------------------------------------------------------------- rule translator
@pytest.mark.parametrize("text, expected", [
    ("20일선 위", {"f": "gap_ma20", "op": ">", "v": 0}),
    ("60일선 아래", {"f": "gap_ma60", "op": "<", "v": 0}),
    ("거래량 1.5배", {"f": "volr", "op": ">=", "v": 1.5}),
    ("거래량 20일 평균 대비 1.5배 미만", {"f": "volr", "op": "<", "v": 1.5}),
    ("거래량 급증", {"f": "volr", "op": ">=", "v": 2.0}),
    ("거래대금 500억 이상", {"f": "val_today", "op": ">=", "v": 5e10}),
    ("거래대금 1.05억원 이하", {"f": "val_today", "op": "<=", "v": 105_000_000}),
    ("거래대금이 붙은", {"f": "val_today", "op": ">=", "v": 1e10}),
    ("3% 이상 상승", {"f": "r1", "op": ">=", "v": 0.03}),
    ("3% 미만 상승", {"f": "r1", "op": "<", "v": 0.03}),
    ("5% 이상 하락", {"f": "r1", "op": "<=", "v": -0.05}),
    ("5% 미만 하락", {"f": "r1", "op": ">", "v": -0.05}),
    ("눌림", {"between": ["dd20", -0.10, -0.03]}),
    ("저변동", {"f": "atr14_pct", "op": "<=", "v": 0.035}),
    ("신고가 근처", {"f": "dd120", "op": ">=", "v": -0.05}),
    ("5000원 이상", {"f": "close", "op": ">=", "v": 5000.0}),
    ("종가 5만원 이하", {"f": "close", "op": "<=", "v": 50_000}),
    ("5일선 위", {"f": "gap_ma5", "op": ">", "v": 0}),
    ("20일선 돌파", {"f": "cross_up_ma20", "op": "==", "v": 1}),
    ("60일 이평선 이탈", {"f": "cross_down_ma60", "op": "==", "v": 1}),
    ("이평선 정배열", {"f": "ma_aligned", "op": "==", "v": 1}),
    ("코스닥", {"market_in": ["KOSDAQ"]}),
    ("반도체", {"sector_any": ["반도체"]}),
])
def test_rule_translator_phrases(text, expected):
    t = RuleTranslator().translate(text)
    assert t.error is None and expected in leaves(t.strategy)


def test_rule_translator_full_sentence():
    t = RuleTranslator().translate("20일선 위 눌림인데 거래량 1.5배 붙은 반도체 코스닥 5개")
    ls = leaves(t.strategy)
    assert {"f": "gap_ma20", "op": ">", "v": 0} in ls
    assert {"f": "volr", "op": ">=", "v": 1.5} in ls
    assert {"sector_any": ["반도체"]} in ls and {"market_in": ["KOSDAQ"]} in ls
    assert t.strategy["top_n"] == 5
    assert t.unparsed == []                                    # every word was understood
    assert t.strategy["rank_by"] == {"f": "quality_score", "desc": True}
    assert t.strategy["guard"]["exclude_risk"] == ["chase", "high_vol"]


@pytest.mark.parametrize("text", ["급등후 눌림목 반등 종목", "급등 한 뒤 눌림목에서 반등한 종목"])
def test_surge_pullback_rebound_is_a_complete_ordered_condition_without_ai(text):
    class UnexpectedProvider:
        def complete_json(self, system, user):
            pytest.fail("a complete ordered condition should use the deterministic translator")

    t = LlmTranslator(UnexpectedProvider()).translate(text)
    assert t.source == "rules" and t.error is None and not t.unparsed and not t.unsupported
    assert leaves(t.strategy) == [{"f": "surge_pullback_rebound", "op": "==", "v": 1}]
    assert t.strategy["guard"]["exclude_risk"] == ["chase", "high_vol"]
    assert any("8%" in a and "3~10%" in a and "1%" in a for a in t.assumptions)


def test_surge_pullback_rebound_composes_with_supported_conditions_and_limits():
    t = RuleTranslator().translate("코스닥 급등 후 눌림목 뒤 반등, 거래량 1.5배 5개")
    assert not t.unparsed and not t.unsupported
    assert {"market_in": ["KOSDAQ"]} in leaves(t.strategy)
    assert {"f": "volr", "op": ">=", "v": 1.5} in leaves(t.strategy)
    assert t.strategy["top_n"] == 5
    t = RuleTranslator().translate("급등 후 눌림목 반등, 배당 3% 이상")
    assert t.strategy is None and t.unsupported


def test_rule_translator_keeps_both_bounds():
    t = RuleTranslator().translate("거래대금 100억 초과, 거래대금 200억 이하")
    assert leaves(t.strategy) == [
        {"f": "val_today", "op": ">", "v": 10_000_000_000},
        {"f": "val_today", "op": "<=", "v": 20_000_000_000},
    ]
    assert not t.unparsed


def test_rule_translator_warns_and_relaxes_guard_for_explicit_chasing():
    t = RuleTranslator().translate("7% 이상 오른 거래량 급증 종목")
    assert "chase" not in t.strategy["guard"]["exclude_risk"]
    assert any("-4.02%" in w for w in t.warnings)


def test_flow_conditions_are_handed_to_the_live_search_not_refused():
    t = RuleTranslator().translate("외국인 순매수 20일선 위 종목")
    assert t.realtime_query == "외국인 순매수"
    assert not any("수급" in u for u in t.unsupported)
    assert {"f": "gap_ma20", "op": ">", "v": 0} in leaves(t.strategy)
    assert all("foreign" not in json.dumps(l) for l in leaves(t.strategy))
    assert RuleTranslator().translate("쌍끌이 매수 종목").realtime_query == "쌍끌이"
    assert RuleTranslator().translate("외국인과 기관 순매수 60일선 위").realtime_query == "외국인과 기관 순매수"
    assert RuleTranslator().translate("외국인과 기관 동반 순매수 60일선 위").realtime_query == "외국인과 기관 순매수"


def test_vague_flow_wording_without_a_live_condition_is_still_refused():
    t = RuleTranslator().translate("수급 좋은 20일선 위 종목")
    assert t.realtime_query is None and any("수급" in u for u in t.unsupported)


@pytest.mark.parametrize("text, expected", [
    ("오늘 기관 순매수 20일선 위", "오늘 기관 순매수"),
    ("최근 5거래일 외국인 순매수 60일선 위", "최근 5거래일 외국인 순매수"),
    ("60일선 위 쌍끌이 최근 2 거래일간", "최근 2 거래일간 쌍끌이"),
    ("오늘 최근 5거래일 기관 순매수 20일선 위", "오늘 최근 5거래일 기관 순매수"),
    ("최근 20거래일 기관 순매수 20일선 위", "최근 20거래일 기관 순매수"),
])
def test_live_flow_handoff_preserves_member_time_window(text, expected):
    # The live parser validates conflicting/unsupported windows; never silently use its default.
    reply = json.dumps({"strategy": {"title": "x", "when": {"f": "gap_ma20", "op": ">", "v": 0},
                                     "rank_by": {"f": "quality_score", "desc": True}, "top_n": 5}})
    for translator in (RuleTranslator(), LlmTranslator(FakeProvider(reply))):
        translated = translator.translate(text)
        assert translated.realtime_query == expected
        assert not any("최근" in word for word in translated.unparsed)


def test_daily_query_without_flow_does_not_gain_a_live_handoff():
    assert RuleTranslator().translate("오늘 20일선 위").realtime_query is None


def test_mixed_flow_query_uses_member_text_without_the_model():
    reply = json.dumps({"strategy": {"title": "x", "when": {"f": "gap_ma20", "op": ">", "v": 0},
                                     "rank_by": {"f": "quality_score", "desc": True}, "top_n": 5},
                        "unsupported": ["외국인 수급 데이터 없음"]})
    t = LlmTranslator(FakeProvider(reply)).translate("기관 순매수 20일선 위")
    assert t.source == "rules" and t.realtime_query == "기관 순매수" and t.unsupported == []


def test_rule_translator_with_nothing_to_parse():
    t = RuleTranslator().translate("내일 오를 종목 추천해줘")
    assert t.strategy is None and t.error
    assert any("추천" in u for u in t.unsupported)


# --------------------------------------------------------------- LLM translator
class FakeProvider:
    name, model = "fake", "fake-1"

    def __init__(self, reply=None, exc=None):
        self.reply, self.exc = reply, exc

    def complete_json(self, system, user):
        assert "피처 목록" in system and "quality_score" in system
        if self.exc:
            raise self.exc
        return self.reply


@pytest.mark.parametrize("text", [
    "20일선 위 또는 60일선 아래",
    "20일선 위이거나 기관 순매수",
    "20일선 위 아닌 종목",
    "20일선 위, 외국인 순매수 5만주 이상",
    "20일선 위, 외국인 순매수 5만원 이상",
    "20일선 위, 가격 5만원 이하, 외국인 순매수",
    "20일선 위, 최근 3개월 외국인 순매수",
    "20일선 위, 최근 3거래일 외국인 순매수 이어지는 종목",
    "20일선 위, 외국인 동반 순매수",
    "오늘 20일선 위, 외국인 순매수",
    "오늘 거래량 2배",
    "20일선 위, 기관·외국인 순매수 중 기관만 확인",
    "20일선 위, PER 10배 미만",
    "10일선 돌파, 거래량 2배",
    "20일선 지지",
])
def test_risky_partial_translation_is_not_executed_or_delegated(text):
    class UnusedProvider(FakeProvider):
        def complete_json(self, system, user):
            pytest.fail("must block ambiguous semantics before requesting AI translation")

    for translator in (RuleTranslator(), LlmTranslator(UnusedProvider())):
        t = translator.translate(text)
        assert t.strategy is None and t.realtime_query is None
        assert t.error and t.unsupported


def test_flow_and_explicit_daily_close_keep_both_conditions():
    t = RuleTranslator().translate("20일선 위, 종가 5만원 이하, 외국인 순매수")
    assert {"f": "close", "op": "<=", "v": 50000} in leaves(t.strategy)
    assert t.realtime_query == "외국인 순매수"
    assert not t.unparsed


def test_valid_ai_output_cannot_reverse_an_explicit_operator():
    reply = json.dumps({"strategy": {"title": "x", "when": {"f": "val_today", "op": ">=", "v": 1e10},
                                     "rank_by": {"f": "quality_score", "desc": True}, "top_n": 10}})
    t = LlmTranslator(FakeProvider(reply)).translate("거래대금 100억 이하 탄력이 단단한")
    assert t.source == "rules" and "검증" in t.fallback_reason
    assert leaves(t.strategy) == [{"f": "val_today", "op": "<=", "v": 1e10}]


@pytest.mark.parametrize("text, expected", [
    ("코스닥 저변동", {"market_in": ["KOSDAQ"]}),
    ("반도체", {"sector_any": ["반도체"]}),
    ("7% 이하 오른", {"f": "r1", "op": "<=", "v": 0.07}),
    ("20일선 위, 외국인 순매수", {"f": "gap_ma20", "op": ">", "v": 0}),
])
def test_complete_rule_results_do_not_call_the_provider(text, expected):
    class UnusedProvider(FakeProvider):
        def complete_json(self, system, user):
            pytest.fail("complete rule translation must not call AI")

    t = LlmTranslator(UnusedProvider()).translate(text)
    assert t.source == "rules" and not t.unparsed
    assert expected in leaves(t.strategy)


def test_partial_ai_translation_preserves_unresolved_words():
    reply = json.dumps({"strategy": {"title": "x", "when": {"f": "gap_ma20", "op": ">", "v": 0.0},
                                     "rank_by": {"f": "quality_score", "desc": True}, "top_n": 10}})
    t = LlmTranslator(FakeProvider(reply)).translate("20일선 위 탄력이 단단한")
    assert t.source == "fake"
    assert t.unparsed == ["탄력이", "단단한"]


def test_partial_ai_translation_cannot_change_scope_guard_or_rank():
    baseline = RuleTranslator().translate("코스닥 저변동, 탄력이 단단한")
    strategy = baseline.strategy
    for altered in (
        {**strategy, "when": {"market_in": ["KOSPI"]}},
        {**strategy, "guard": {"exclude_risk": []}},
        {**strategy, "rank_by": {"f": "vol20", "desc": True}},
    ):
        reply = json.dumps({"strategy": altered})
        t = LlmTranslator(FakeProvider(reply)).translate("코스닥 저변동, 탄력이 단단한")
        assert t.source == "rules" and "검증" in t.fallback_reason
        assert t.strategy == baseline.strategy and t.unparsed == baseline.unparsed


def test_llm_translation_that_validates_is_used(tmp_path):
    reply = json.dumps({"strategy": {"title": "x", "when": {"f": "gap_ma60", "op": ">", "v": 0},
                                     "rank_by": {"f": "quality_score", "desc": True}, "top_n": 7,
                                     "guard": {"exclude_risk": ["chase"]}},
                        "assumptions": ["추세 = 60일선 위"], "unsupported": []})
    led = Ledger(tmp_path / "l.db")
    t = LlmTranslator(FakeProvider(reply), ledger=led).translate("완만하게 오르는 종목 7개")
    assert t.source == "fake" and t.strategy["top_n"] == 7 and t.fallback_reason is None
    assert led.llm_usage()[0]["calls"] == 1


def test_llm_inventing_a_feature_falls_back_to_rules():
    reply = json.dumps({"strategy": {"title": "x", "when": {"f": "foreign_net_buy", "op": ">", "v": 0},
                                     "rank_by": {"f": "r1"}, "top_n": 5}})
    t = LlmTranslator(FakeProvider(reply)).translate("20일선 위 탄력이 단단한")
    assert t.source == "rules" and "검증" in t.fallback_reason
    assert {"f": "gap_ma20", "op": ">", "v": 0} in leaves(t.strategy)


def test_llm_outage_falls_back_to_rules():
    t = LlmTranslator(FakeProvider(exc=TimeoutError("slow"))).translate("저변동 탄력이 단단한")
    assert t.source == "rules" and "호출" in t.fallback_reason


def test_failing_provider_hands_over_to_the_next_before_rules(tmp_path):
    reply = json.dumps({"strategy": {"title": "x", "when": {"f": "gap_ma60", "op": ">", "v": 0},
                                     "rank_by": {"f": "quality_score", "desc": True}, "top_n": 7}})
    second = FakeProvider(reply)
    second.name = "fake2"
    led = Ledger(tmp_path / "l.db")
    tr = LlmTranslator([FakeProvider(exc=PermissionError("403")), second], ledger=led)
    t = tr.translate("완만하게 오르는 종목 7개")
    assert t.source == "fake2" and t.fallback_reason is None
    assert tr.chain == "fake→fake2→rules"
    assert sum(r["calls"] for r in led.llm_usage()) == 2


def test_explanations_must_be_grounded_in_engine_numbers():
    facts = "20일 고가 대비 -3.1% · ATR 2.4% · 거래량 1.6배"
    assert numbers_are_grounded("고가 대비 -3.1% 아래, 거래량 1.6배입니다", facts)
    assert not numbers_are_grounded("목표가 12,000원, 상승 여력 18%", facts)


# ------------------------------------------------------------------------- API
@pytest.fixture
def client(engine, store, tmp_path):
    svc = EngineService(store=store, ledger=Ledger(tmp_path / "l.db"), translator=RuleTranslator(), engine=engine)
    return TestClient(create_app(svc, autoload=False))


def test_health_and_catalogue(client):
    h = client.get("/engine/health").json()
    assert h["state"] == "ready" and h["codes"] > 0 and h["translator"] == "rules"
    f = client.get("/engine/features").json()
    assert {"r1", "quality_score"} <= {x["name"] for x in f["features"]}


def test_translate_then_screen_then_backtest(client):
    t = client.post("/engine/translate", json={"text": "20일선 위 저변동 10개"}).json()
    assert t["strategy"]
    s = client.post("/engine/screen", json={"strategy": t["strategy"]}).json()
    assert "run_id" in s and s["counts"]["published"] == len(s["results"])
    b = client.post("/engine/backtest", json={"strategy": t["strategy"]}).json()
    assert b["horizons"]["5"]["verdict"]["label"]


def test_surge_pullback_rebound_api_executes_the_complete_strategy(client):
    translation = client.post("/engine/translate", json={"text": "급등후 눌림목 반등 종목"}).json()
    assert translation["strategy"] and not translation["unparsed"]
    response = client.post("/engine/screen", json={"strategy": translation["strategy"]})
    assert response.status_code == 200
    row = response.json()
    assert row["as_of"] and row["counts"]["published"] == len(row["results"])


def test_library_strategy_runs_and_retired_one_is_refused(client):
    assert client.post("/engine/screen", json={"strategy_id": "lowvol_uptrend"}).status_code == 200
    r = client.post("/engine/screen", json={"strategy_id": "chase_gainers"})
    assert r.status_code == 409
    assert client.post("/engine/backtest", json={"strategy_id": "chase_gainers"}).status_code == 200


def test_invalid_dsl_is_a_422_not_a_500(client):
    bad = {"title": "x", "when": {"f": "made_up", "op": ">", "v": 1}, "rank_by": {"f": "r1"}, "top_n": 5}
    r = client.post("/engine/screen", json={"strategy": bad})
    assert r.status_code == 422 and "알 수 없는 피처" in r.text


def test_risk_and_scorecard_endpoints(client):
    r = client.post("/engine/risk", json={"codes": ["000010"]}).json()
    assert r["items"][0]["found"]
    client.post("/engine/screen", json={"strategy_id": "engine_default"})
    card = client.get("/engine/scorecard", params={"strategy_id": "engine_default"}).json()
    assert card["runs"] == 1


def test_service_reports_loading_as_503(store, tmp_path):
    svc = EngineService(store=store, ledger=Ledger(tmp_path / "l.db"), translator=RuleTranslator())
    c = TestClient(create_app(svc, autoload=False))
    r = c.post("/engine/screen", json={"strategy_id": "lowvol_uptrend"})
    assert r.status_code == 503
