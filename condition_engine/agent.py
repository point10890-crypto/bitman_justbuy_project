"""Natural language -> condition DSL.

Two translators share one contract and one validator:

  * RuleTranslator  deterministic Korean phrase grammar. Always available, no cost.
  * LlmTranslator   OpenAI or Gemini with JSON output. Optional. Its output is
                    validated against the DSL whitelist; anything that fails falls
                    back to the rule translator, and the response says so.

Neither translator picks stocks or produces prices. A request for data the engine
does not have (investor flows, disclosures, news, valuation) is reported as
unsupported instead of being approximated.
"""
from __future__ import annotations

import json
import logging
import os
import re
import time
from dataclasses import dataclass, field
from typing import Protocol

from . import config, dsl
from .features import REGISTRY

log = logging.getLogger(__name__)

SECTORS = {
    "반도체": ["반도체"],
    "2차전지": ["전지", "축전지", "배터리"], "이차전지": ["전지", "축전지", "배터리"], "배터리": ["전지", "축전지", "배터리"],
    "바이오": ["의약", "제약", "바이오", "생물"], "제약": ["의약", "제약"],
    "자동차": ["자동차"], "조선": ["선박", "조선"], "방산": ["무기", "방위", "항공기"],
    "은행": ["은행"], "금융": ["은행", "금융", "증권", "보험"], "증권": ["증권"],
    "게임": ["게임"], "화장품": ["화장품"], "로봇": ["로봇"],
    "원전": ["원자력", "발전"], "전력": ["전기", "발전", "전력"], "건설": ["건설"],
    "화학": ["화학"], "철강": ["철강", "1차 금속"], "엔터": ["오디오물", "영상", "연예"],
    "소프트웨어": ["소프트웨어", "프로그래밍"], "통신": ["통신"], "식품": ["식료품", "식품"],
    "유통": ["소매", "유통"], "기계": ["기계"], "디스플레이": ["디스플레이", "전자부품"],
}

UNSUPPORTED = [
    (re.compile(r"외국인|기관|수급|순매수|매집 주체|개인 매도"), "투자자별 수급 데이터는 아직 엔진에 없습니다"),
    (re.compile(r"공시|자사주|무상증자|유상증자|수주"), "공시 이벤트 데이터는 아직 엔진에 없습니다"),
    (re.compile(r"뉴스|재료|테마|이슈|호재|악재"), "뉴스·테마 해석은 아직 조건으로 만들 수 없습니다"),
    (re.compile(r"PER|PBR|ROE|배당|실적|영업이익|매출"), "재무·밸류에이션 데이터는 아직 엔진에 없습니다"),
    (re.compile(r"목표가|추천|사야|매수할|오를 종목"), "엔진은 매수 추천이나 목표가를 만들지 않습니다 — 조건과 그 과거 성적을 보여줍니다"),
]

# Investor-flow phrases. The engine has no flow data, but the live data search does, so these
# are handed back as `realtime_query` and applied there instead of being refused.
REALTIME = re.compile(r"쌍끌이|(?:외국인|기관)(?:\s*(?:과|와|및|,)?\s*(?:외국인|기관))?\s*(?:동반\s*)?순매수")
FLOW_WORDS = re.compile(r"수급|외국인|기관|순매수|쌍끌이")
FLOW_WINDOW = re.compile(r"오늘|최근\s*[0-9]+\s*거래일(?:간)?")


def realtime_query(text: str) -> str | None:
    found: list[str] = []
    for m in REALTIME.finditer(text or ""):
        phrase = re.sub(r"\s+", " ", m.group()).strip()
        if phrase not in found:
            found.append(phrase)
    if not found:
        return None
    # Keep explicit periods, including invalid/conflicting ones, for the live parser to validate.
    # Dropping them would silently switch a 'today' query to its three-session default.
    windows = [re.sub(r"\s+", " ", m.group()).strip() for m in FLOW_WINDOW.finditer(text or "")]
    return " ".join(windows + found)


def _drop_flow_refusals(unsupported, rq: str | None) -> list[str]:
    if not rq:
        return sorted(set(unsupported))
    return sorted({u for u in unsupported if not FLOW_WORDS.search(u)})


@dataclass
class Translation:
    strategy: dict | None
    source: str                              # rules | openai | gemini
    conditions: list[str] = field(default_factory=list)
    assumptions: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    unsupported: list[str] = field(default_factory=list)
    unparsed: list[str] = field(default_factory=list)
    error: str | None = None
    fallback_reason: str | None = None
    realtime_query: str | None = None       # flow conditions for the live data search

    def as_dict(self) -> dict:
        return self.__dict__.copy()


# ------------------------------------------------------------------- rule translator
class RuleTranslator:
    def translate(self, text: str) -> Translation:
        raw = (text or "").strip()
        t = re.sub(r"\s+", " ", raw)
        leaves: list[dict] = []
        assumptions: list[str] = []
        warnings: list[str] = []
        rq = realtime_query(t)
        unsupported = _drop_flow_refusals({msg for pat, msg in UNSUPPORTED if pat.search(t)}, rq)
        rank_by = None
        top_n = 10
        guard = {"exclude_risk": list(dsl.DEFAULT_GUARD["exclude_risk"])}
        consumed: list[tuple[int, int]] = []

        def take(m):
            consumed.append(m.span())

        for m in re.finditer(r"(\d+)\s*일\s*(?:이평선|이동평균선|이평|선)\s*(위|아래|돌파|이탈|지지)", t):
            n, side = int(m.group(1)), m.group(2)
            take(m)
            if n not in (20, 60, 120):
                near = min((20, 60, 120), key=lambda x: abs(x - n))
                assumptions.append(f"{n}일선은 지원하지 않아 {near}일선으로 바꿨습니다")
                n = near
            op = "<" if side in ("아래", "이탈") else ">"
            leaves.append({"f": f"gap_ma{n}", "op": op, "v": 0})
        if (m := re.search(r"정배열", t)):
            take(m)
            leaves += [{"f": f"gap_ma{n}", "op": ">", "v": 0} for n in (20, 60, 120)]
            assumptions.append("정배열 = 20·60·120일선 위")
        if (m := re.search(r"(얕은 )?눌림", t)):
            take(m)
            shallow = bool(m.group(1))
            leaves.append({"between": ["dd20", -0.06 if shallow else -0.10, -0.02 if shallow else -0.03]})
            assumptions.append("눌림 = 20일 고가 대비 " + ("-2~-6%" if shallow else "-3~-10%"))
        if (m := re.search(r"(신고가|고점|고가)\s*(근처|근접|부근|가까운)", t)):
            take(m)
            leaves.append({"f": "dd120", "op": ">=", "v": -0.05})
            assumptions.append("신고가 근처 = 120일 고가 대비 -5% 이내")
        if (m := re.search(r"신고가\s*(돌파|갱신)", t)):
            take(m)
            leaves.append({"f": "new_high120", "op": "==", "v": 1})
            warnings.append("거래량을 동반한 120일 신고가 돌파는 과거 5일 성과가 음수였습니다(-1.60% / -2.47%)")
        if (m := re.search(r"거래량\s*(\d+(?:\.\d+)?)\s*배", t)):
            take(m)
            leaves.append({"f": "volr", "op": ">=", "v": float(m.group(1))})
        elif (m := re.search(r"거래량\s*(?:이\s*)?(급증|폭발|터진|붙은|증가|늘어난|많은)", t)):
            take(m)
            strong = m.group(1) in ("급증", "폭발", "터진")
            leaves.append({"f": "volr", "op": ">=", "v": 2.0 if strong else 1.5})
            assumptions.append(f"거래량 {m.group(1)} = 직전 20일 평균의 {2.0 if strong else 1.5}배 이상")
        if (m := re.search(r"거래대금\s*(\d+(?:,\d{3})*(?:\.\d+)?)\s*(억|조)", t)):
            take(m)
            n = float(m.group(1).replace(",", ""))
            leaves.append({"f": "val_today", "op": ">=", "v": n * (1e12 if m.group(2) == "조" else 1e8)})
        elif (m := re.search(r"거래대금\s*(?:이\s*)?(붙은|많은|큰|터진)", t)):
            take(m)
            leaves.append({"f": "val_today", "op": ">=", "v": 1e10})
            assumptions.append("거래대금이 붙은 = 당일 거래대금 100억원 이상")
        if (m := re.search(r"(\d+(?:\.\d+)?)\s*%?\s*[~\-]\s*(\d+(?:\.\d+)?)\s*%\s*(?:상승|오른|올랐)", t)):
            take(m)
            lo, hi = sorted((float(m.group(1)) / 100, float(m.group(2)) / 100))
            leaves.append({"between": ["r1", lo, hi]})
        elif (m := re.search(r"(?:상승률\s*)?(\d+(?:\.\d+)?)\s*%\s*(?:이상\s*)?(?:상승|오른|올랐|급등)", t)):
            take(m)
            leaves.append({"f": "r1", "op": ">=", "v": float(m.group(1)) / 100})
        if (m := re.search(r"(\d+(?:\.\d+)?)\s*%\s*(?:이상\s*)?(?:하락|빠진|떨어진)", t)):
            take(m)
            leaves.append({"f": "r1", "op": "<=", "v": -float(m.group(1)) / 100})
        if (m := re.search(r"저변동|변동성\s*(?:이\s*)?(?:낮은|작은)|차분한|안정적인?", t)):
            take(m)
            leaves.append({"f": "atr14_pct", "op": "<=", "v": 0.035})
            rank_by = rank_by or {"f": "vol20", "desc": False}
            assumptions.append("저변동 = ATR 비율 3.5% 이하")
        if (m := re.search(r"변동성\s*(?:이\s*)?(?:큰|높은)|고변동", t)):
            take(m)
            leaves.append({"f": "atr14_pct", "op": ">=", "v": 0.05})
            guard["exclude_risk"] = [f for f in guard["exclude_risk"] if f != "high_vol"]
            warnings.append("고변동 종목은 가장 견고하게 부진했던 집단입니다(ATR 상위 10% 5일 -1.07%). 요청대로 고변동 제외를 껐습니다")
        if (m := re.search(r"상승\s*추세|우상향|추세가?\s*좋은", t)):
            take(m)
            leaves += [{"f": "gap_ma60", "op": ">", "v": 0}, {"f": "r20", "op": ">", "v": 0}]
            assumptions.append("상승 추세 = 60일선 위 + 20일 수익률 양수")
        if (m := re.search(r"과매도|낙폭\s*과대|많이\s*빠진", t)):
            take(m)
            leaves.append({"f": "r5", "op": "<=", "v": -0.10})
            warnings.append("단기 과매도 반등은 두 구간 모두 음수였습니다(-0.55% / -0.38%), 하락장에서는 -2.06%")
        if (m := re.search(r"(\d+(?:,\d{3})*)\s*원\s*(이상|이하)", t)):
            take(m)
            leaves.append({"f": "close", "op": ">=" if m.group(2) == "이상" else "<=", "v": float(m.group(1).replace(",", ""))})
        if (m := re.search(r"대형주|우량주", t)):
            take(m)
            leaves.append({"f": "val_avg20", "op": ">=", "v": 3e10})
            assumptions.append("대형주 = 20일 평균 거래대금 300억원 이상")
        if (m := re.search(r"중소형주?", t)):
            take(m)
            leaves.append({"f": "val_avg20", "op": "<=", "v": 1e10})
            assumptions.append("중소형 = 20일 평균 거래대금 100억원 이하")

        markets = [mk for mk, word in (("KOSPI", "코스피"), ("KOSDAQ", "코스닥")) if word in t]
        if markets:
            leaves.append({"market_in": markets})
        sector_words = []
        for word, keys in SECTORS.items():
            if word in t:
                sector_words += [k for k in keys if k not in sector_words]
        if sector_words:
            leaves.append({"sector_any": sector_words})

        if (m := re.search(r"(?:상위\s*)?(\d+)\s*(?:개|종목)", t)):
            top_n = max(1, min(int(m.group(1)), dsl.MAX_TOP_N))
            take(m)
        if re.search(r"거래대금\s*(?:많은|큰)?\s*순", t):
            rank_by = {"f": "val_today", "desc": True}
        elif re.search(r"(?:업종\s*대비\s*)?강한\s*순|상대\s*강도\s*순", t):
            rank_by = {"f": "rs_sector20", "desc": True}
        elif re.search(r"변동성\s*낮은\s*순", t):
            rank_by = {"f": "vol20", "desc": False}

        if any(l.get("f") == "r1" and l.get("op") == ">=" and l.get("v", 0) >= 0.05 for l in leaves) or \
                any("between" in l and l["between"][0] == "r1" and l["between"][2] >= 0.05 for l in leaves):
            guard["exclude_risk"] = [f for f in guard["exclude_risk"] if f != "chase"]
            warnings.append("당일 +5% 이상 상승 조건은 급등 추격과 겹칩니다. 같은 방식은 과거 5일 -2.58% / -4.02%였습니다. "
                            "요청대로 추격 제외를 껐습니다")

        for m in REALTIME.finditer(t):
            consumed.append(m.span())
        if rq:
            consumed.extend(m.span() for m in FLOW_WINDOW.finditer(t))

        if not leaves:
            return Translation(None, "rules", unsupported=unsupported, realtime_query=rq,
                               error="조건으로 바꿀 수 있는 표현을 찾지 못했습니다. 예: '20일선 위 눌림, 거래량 1.5배, 반도체 10개'")

        strategy = {
            "title": raw[:60] or "사용자 조건",
            "when": {"all": leaves} if len(leaves) > 1 else leaves[0],
            "rank_by": rank_by or {"f": "quality_score", "desc": True},
            "top_n": top_n,
            "guard": guard,
        }
        if rank_by is None:
            assumptions.append("정렬 기준을 말하지 않아 엔진 기본 점수(고가 근접 + 낮은 ATR) 순으로 정렬합니다")
        strategy = dsl.validate_strategy(strategy)
        return Translation(strategy, "rules", conditions=dsl.describe(strategy["when"]),
                           assumptions=assumptions, warnings=warnings, unsupported=unsupported,
                           unparsed=_leftover(t, consumed), realtime_query=rq)


def _leftover(text: str, spans: list[tuple[int, int]]) -> list[str]:
    chars = list(text)
    for a, b in spans:
        for i in range(a, b):
            chars[i] = " "
    rest = "".join(chars)
    stop = r"(종목|찾아줘|찾아|보여줘|알려줘|중에|중|에서|이고|이면서|하고|그리고|있는|인|은|는|이|가|을|를|과|와|및|,|\.|!|\?)"
    rest = re.sub(stop, " ", rest)
    words = [w for w in rest.split() if len(w) >= 2 and not re.fullmatch(r"\d+", w)]
    known = set(SECTORS) | {"코스피", "코스닥", "상위", "순"}
    return [w for w in words if w not in known][:8]


# -------------------------------------------------------------------- LLM translator
class Provider(Protocol):
    name: str
    model: str

    def complete_json(self, system: str, user: str) -> str: ...


class OpenAIProvider:
    name = "openai"

    def __init__(self, api_key: str, model: str | None = None, timeout: float = 25.0):
        self.api_key, self.timeout = api_key, timeout
        # The project key only has access to the model the Spring Boot ChatGptAgent uses.
        self.model = model or os.getenv("CONDITION_ENGINE_OPENAI_MODEL", "gpt-5.5")

    def complete_json(self, system: str, user: str) -> str:
        import httpx
        body = {"model": self.model, "response_format": {"type": "json_object"},
                "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]}
        if not self.model.startswith(("gpt-5", "o")):
            body["temperature"] = 0       # reasoning models accept only the default temperature
        r = httpx.post("https://api.openai.com/v1/chat/completions", timeout=self.timeout,
                       headers={"Authorization": f"Bearer {self.api_key}"}, json=body)
        r.raise_for_status()
        return r.json()["choices"][0]["message"]["content"]


class GeminiProvider:
    name = "gemini"

    def __init__(self, api_key: str, model: str | None = None, timeout: float = 25.0):
        self.api_key, self.timeout = api_key, timeout
        self.model = model or os.getenv("CONDITION_ENGINE_GEMINI_MODEL", "gemini-2.5-flash")

    def complete_json(self, system: str, user: str) -> str:
        import httpx
        r = httpx.post(f"https://generativelanguage.googleapis.com/v1beta/models/{self.model}:generateContent",
                       timeout=self.timeout, headers={"x-goog-api-key": self.api_key},
                       json={"systemInstruction": {"parts": [{"text": system}]},
                             "contents": [{"role": "user", "parts": [{"text": user}]}],
                             "generationConfig": {"temperature": 0, "responseMimeType": "application/json"}})
        r.raise_for_status()
        return r.json()["candidates"][0]["content"]["parts"][0]["text"]


def system_prompt() -> str:
    catalogue = "\n".join(f"- {d.name} ({d.label}, 단위 {d.unit}): {d.description}" for d in REGISTRY.values())
    return f"""너는 한국 주식 조건검색 DSL 번역기다. 회원의 요청을 아래 문법의 JSON 으로만 바꾼다.
종목을 고르거나, 종목명·종목코드·가격·목표가를 만들거나, 매수 의견을 내지 않는다.

피처 목록 (이 이름만 쓸 수 있다):
{catalogue}

조건 문법:
  {{"all":[...]}} | {{"any":[...]}} | {{"not":조건}}
  {{"f":피처,"op":">=|>|<=|<|==","v":숫자}}
  {{"between":[피처,하한,상한]}}
  {{"sector_any":["업종 키워드",...]}}      예: ["반도체"], ["의약","제약"]
  {{"market_in":["KOSPI"|"KOSDAQ"]}}
비율은 소수(0.05 = 5%), 금액은 원 단위 숫자.

출력 JSON:
{{"strategy":{{"title":문자열,"when":조건,"rank_by":{{"f":피처,"desc":true|false}},"top_n":1~50,
  "guard":{{"exclude_risk":["chase","high_vol","breakout_spike","volume_spike_flat"] 중 일부}}}},
 "assumptions":["모호한 표현을 어떤 수치로 해석했는지"],
 "unsupported":["데이터가 없어 조건으로 만들 수 없는 요청"]}}

규칙:
- 외국인·기관 순매수(수급)는 실시간 검색이 따로 적용한다 → 조건에 넣지 않고 unsupported 에도 적지 않는다.
- 공시, 뉴스, 테마, 재무, 밸류에이션은 데이터가 없다 → unsupported 에 적고 조건에 넣지 않는다.
- 정렬을 말하지 않으면 rank_by 는 {{"f":"quality_score","desc":true}}.
- guard 는 기본 ["chase","high_vol"]. 회원이 급등주나 고변동을 명시적으로 원할 때만 해당 항목을 뺀다.
- 개수를 말하지 않으면 top_n 은 10."""


class LlmTranslator:
    """Tries each provider in order; the first answer that passes the DSL validator wins.

    Every attempt is logged. If no provider yields a valid strategy, the rule translator
    answers, so translation never depends on an outside service being up.
    """

    def __init__(self, providers, fallback: RuleTranslator | None = None, ledger=None):
        self.providers = list(providers) if isinstance(providers, (list, tuple)) else [providers]
        self.fallback, self.ledger = fallback or RuleTranslator(), ledger

    @property
    def provider(self):
        return self.providers[0]

    @property
    def chain(self) -> str:
        return "→".join([p.name for p in self.providers] + ["rules"])

    def translate(self, text: str) -> Translation:
        invalid = False
        for provider in self.providers:
            started = time.perf_counter()
            ok, note = False, ""
            try:
                raw = provider.complete_json(system_prompt(), text)
                payload = json.loads(raw)
                strategy = dsl.validate_strategy(payload["strategy"])
                ok = True
                rq = realtime_query(text)        # from the member's own words, never from the model
                return Translation(strategy, provider.name, conditions=dsl.describe(strategy["when"]),
                                   assumptions=[str(a) for a in payload.get("assumptions", [])][:8],
                                   unsupported=_drop_flow_refusals(
                                       {str(u) for u in payload.get("unsupported", [])}
                                       | {m for p, m in UNSUPPORTED if p.search(text)}, rq),
                                   realtime_query=rq)
            except Exception as exc:
                note = f"{type(exc).__name__}: {str(exc)[:150]}"
                invalid = invalid or isinstance(exc, (dsl.DslError, KeyError, TypeError, json.JSONDecodeError))
            finally:
                if self.ledger is not None:
                    self.ledger.log_llm("translate", provider.name, provider.model, ok,
                                        int((time.perf_counter() - started) * 1000), note)
        result = self.fallback.translate(text)
        result.fallback_reason = ("AI 번역이 검증을 통과하지 못해 규칙 번역을 사용했습니다" if invalid
                                  else "AI 번역 호출에 실패해 규칙 번역을 사용했습니다")
        return result


def build_translator(mode: str | None = None, ledger=None):
    """auto: every provider with a key, OpenAI then Gemini, then rules. none: rules only."""
    _load_env()
    mode = (mode or os.getenv("CONDITION_ENGINE_LLM", "auto")).lower()
    rules = RuleTranslator()
    if mode == "none":
        return rules
    openai_key = os.getenv("OPENAI_API_KEY")
    gemini_key = os.getenv("GEMINI_API_KEY") or os.getenv("GOOGLE_API_KEY")
    providers = []
    if mode in ("auto", "openai") and openai_key:
        providers.append(OpenAIProvider(openai_key))
    if mode in ("auto", "gemini") and gemini_key:
        providers.append(GeminiProvider(gemini_key))
    return LlmTranslator(providers, rules, ledger) if providers else rules


def _load_env() -> None:
    try:
        from dotenv import load_dotenv
    except ImportError:
        return
    for path in (config.PROJECT_DIR / ".env", config.PROJECT_DIR / "backend" / ".env"):
        if path.exists():
            load_dotenv(path, override=False)
