# Ordered source expansion after the repair gate

The user chose free/low-cost first. KIS third-party/commercial usage rights need confirmation; there is no ECOS key yet. New features must default to disabled, with no paid call or account creation during fixture verification.

## KIS evidence

- Preserve the root's fluctuation ranking/current-price repairs. Add bounded minute and per-stock program evidence for at most five deterministic candidates.
- Official contracts: `inquire-time-itemchartprice`, TR `FHKST03010200`, KRX market `J`, stock code, current Korean input time, past-data flag `Y`; at most 30 rows per call, current-day rows only. Keep the most recent incomplete minute out of aggregates. Preserve actual `stck_bsop_date` and `stck_cntg_hour`.
- `program-trade-by-stock`, TR `FHPPG04650101`, market `J`, six-digit code. Confirm fields from the official check example before implementation. Distinguish provider capture time from business/trade time; do not fabricate a business date absent from the response.
- Never call a recent-bar weighted average a full-session VWAP. Label coverage explicitly, and only calculate from valid price/volume rows.
- Source status and provenance must reach the existing prompt/evidence flow, not an unused service. Missing/bad/stale data stays unavailable; no LLM invention and no orders.
- `BITMAN_KIS_EXTENDED_EVIDENCE_ENABLED=false` by default. A shared conservative throttle on KIS REST calls is preferable to per-method burst retries. No actual API calls in tests.

## DART event evidence

- Add structured buyback `tsstkAqDecsn.json`, convertible bond `cvbdIsDecsn.json`, rights issue `piicDecsn.json` facts; verify official field contracts. Preserve receipts, stock/corp identity, observation date, capture time and source links.
- For supply contracts/shareholder changes use existing list + original `document.xml` ZIP. Do not invent dedicated endpoints. At most five stocks, three structured calls plus one bounded list call, and two selected original documents per stock; cache valid results 15 minutes. Feature disabled by default (`BITMAN_DART_EVENT_EVIDENCE_ENABLED=false`) until runtime fixture review.
- Never label a report title as verified contract amount or shareholder percentage. If XML is ambiguous, provide the receipt/link and explicit unknown fields. Flag amendments/corrections; no unqualified positive score from an amended title.
- Disable XML external entities/DOCTYPE, bound compressed and expanded bytes/entries; never extract ZIP paths to disk. Validate six-digit stock/eight-digit corp/fourteen-digit receipt IDs.
- Provider status 013 is normal empty; 010/011/012 authentication and 020 quota remain errors. Do not cache errors as healthy empty. Logs must not expose request URLs with key or raw exceptions.
- Connect bounded selected-candidate facts into orchestrator context without altering deterministic candidate selection or overwriting publication/price validation.

## ECOS market context

Implement a separate short-timeout client, default `BITMAN_ECOS_ENABLED=false`, `ECOS_API_KEY` unset. No key value in documentation/logs/status. Three official sample metadata checks on 2026-10-11 confirmed:

| Metric | Table | Item | Cycle | Unit |
| --- | --- | --- | --- | --- |
| USD/KRW reference rate | 731Y001 | 0000001 | D | 원 |
| Korean government bond 3y | 817Y002 | 010200000 | D | 연% |
| BOK policy rate | 722Y001 | 0101000 | D | 연% |

Metadata sources: `https://ecos.bok.or.kr/api/StatisticItemList/sample/json/kr/1/10/731Y001`, `.../11/20/817Y002`, `.../1/10/722Y001` (official public sample key, no account credential).

Keep real observation periods from `TIME`, source `한국은행 ECOS`, unit and `fetchedAt`; release time remains unknown if not supplied. Reject wrong series, malformed/nonfinite/future/stale observations. Bound requests and cached time. Make disabled/missing-key/provider-error/partial/stale explicit. Append context to the existing market prompt, with a note that it is published daily statistics and not a live quote. Do not turn missing macro data into a BUY score.

## Optional news

Brave is optional and stays disabled pending key, budget and explicit application/storage rights. No raw snippets/search results may enter persistent analysis caches under unconfirmed storage rights. No NAVER Hub/KRX/FRED addition or paid service signup.
