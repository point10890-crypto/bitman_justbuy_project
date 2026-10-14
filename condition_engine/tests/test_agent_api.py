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
    ("거래량 급증", {"f": "volr", "op": ">=", "v": 2.0}),
    ("거래대금 500억 이상", {"f": "val_today", "op": ">=", "v": 5e10}),
    ("거래대금이 붙은", {"f": "val_today", "op": ">=", "v": 1e10}),
    ("3% 이상 상승", {"f": "r1", "op": ">=", "v": 0.03}),
    ("5% 이상 하락", {"f": "r1", "op": "<=", "v": -0.05}),
    ("눌림", {"between": ["dd20", -0.10, -0.03]}),
    ("저변동", {"f": "atr14_pct", "op": "<=", "v": 0.035}),
    ("신고가 근처", {"f": "dd120", "op": ">=", "v": -0.05}),
    ("5000원 이상", {"f": "close", "op": ">=", "v": 5000.0}),
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


def test_rule_translator_warns_and_relaxes_guard_for_explicit_chasing():
    t = RuleTranslator().translate("오늘 7% 이상 오른 거래량 급증 종목")
    assert "chase" not in t.strategy["guard"]["exclude_risk"]
    assert any("-4.02%" in w for w in t.warnings)


def test_rule_translator_refuses_data_it_does_not_have():
    t = RuleTranslator().translate("외국인 순매수 이어지는 20일선 위 종목")
    assert any("수급" in u for u in t.unsupported)
    assert all("foreign" not in json.dumps(l) for l in leaves(t.strategy))


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


def test_llm_translation_that_validates_is_used(tmp_path):
    reply = json.dumps({"strategy": {"title": "x", "when": {"f": "gap_ma60", "op": ">", "v": 0},
                                     "rank_by": {"f": "quality_score", "desc": True}, "top_n": 7,
                                     "guard": {"exclude_risk": ["chase"]}},
                        "assumptions": ["추세 = 60일선 위"], "unsupported": []})
    led = Ledger(tmp_path / "l.db")
    t = LlmTranslator(FakeProvider(reply), ledger=led).translate("추세 좋은 종목 7개")
    assert t.source == "fake" and t.strategy["top_n"] == 7 and t.fallback_reason is None
    assert led.llm_usage()[0]["calls"] == 1


def test_llm_inventing_a_feature_falls_back_to_rules():
    reply = json.dumps({"strategy": {"title": "x", "when": {"f": "foreign_net_buy", "op": ">", "v": 0},
                                     "rank_by": {"f": "r1"}, "top_n": 5}})
    t = LlmTranslator(FakeProvider(reply)).translate("20일선 위")
    assert t.source == "rules" and "검증" in t.fallback_reason
    assert {"f": "gap_ma20", "op": ">", "v": 0} in leaves(t.strategy)


def test_llm_outage_falls_back_to_rules():
    t = LlmTranslator(FakeProvider(exc=TimeoutError("slow"))).translate("저변동")
    assert t.source == "rules" and "호출" in t.fallback_reason


def test_failing_provider_hands_over_to_the_next_before_rules(tmp_path):
    reply = json.dumps({"strategy": {"title": "x", "when": {"f": "gap_ma60", "op": ">", "v": 0},
                                     "rank_by": {"f": "quality_score", "desc": True}, "top_n": 7}})
    second = FakeProvider(reply)
    second.name = "fake2"
    led = Ledger(tmp_path / "l.db")
    tr = LlmTranslator([FakeProvider(exc=PermissionError("403")), second], ledger=led)
    t = tr.translate("추세 좋은 종목 7개")
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
