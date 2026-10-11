# DART event evidence design

Status: approved design implemented after repair Gate A; all 45 owned DART cases pass in root's full Gate B (472 backend/164 frontend). No provider call or key access was performed.

## Official contracts checked on 2026-10-11

All three structured endpoints accept `corp_code`, `bgn_de`, `end_de` plus authentication. Preserve `rcept_no` (14 digits), `corp_code` (8 digits), `corp_name` and the requested stock identity. Preserve event dates from the relevant documented fields, and the filing date only when supplied by a matching list row or unambiguous original document. Capture date/instant identify when this service observed the provider facts; they do not invent a filing/business timestamp.

| Event | Endpoint | Facts retained from documented fields |
| --- | --- | --- |
| Buyback | `tsstkAqDecsn.json` | `aqpln_stk_ostk`, `aqpln_stk_estk`, `aqpln_prc_ostk`, `aqpln_prc_estk`, `aqexpd_bgd`, `aqexpd_edd`, `aq_pp`, `aq_mth`, `aq_dd` |
| Convertible bond | `cvbdIsDecsn.json` | `bd_fta`, `bd_intr_ex`, `bd_intr_sf`, `bd_mtd`, `cv_prc`, `cvisstk_cnt`, `cvisstk_tisstk_vs`, `cvrqpd_bgd`, `cvrqpd_edd`, `pymd`, `bddd` |
| Rights issue | `piicDecsn.json` | `nstk_ostk_cnt`, `nstk_estk_cnt`, `bfic_tisstk_ostk`, `bfic_tisstk_estk`, `fdpp_fclt`, `fdpp_op`, `fdpp_dtrp`, `fdpp_ocsa`, `fdpp_etc`, `ic_mthn` |

Sources: [buyback guide](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS005&apiId=2020038), [convertible bond guide](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS005&apiId=2020033), [rights issue guide](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS005&apiId=2020023).

Use one `list.json` request per stock for the last 30 days, page 1, at most 20 rows, descending filing date, including amendments (`last_reprt_at=N`). Select at most two supply-contract/shareholder filings by recognized report category/title; preserve list-provided `rcept_dt`, `report_nm`, `rm` and link. The report title selects a document to inspect; it does not verify its amount or ownership percentage. [Disclosure search guide](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001).

`document.xml` accepts a 14-digit receipt number and returns a ZIP binary containing original disclosure files. It has no dedicated contract/shareholder numeric API contract. Preserve the viewer URL `https://dart.fss.or.kr/dsaf001/main.do?rcpNo=<receipt>`. [Original document guide](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019003).

## Service and integration

Add `DartEventEvidenceService` with a bounded batch API accepting selected six-digit stock codes, preserving order, deduplicating, and limiting to five. It returns an immutable snapshot with per-stock identity, observation/capture times, source URLs, event type, raw documented facts, amendment flags, unknown fields and statuses. Disabled/missing-key/provider-error/partial/empty are explicit; no event or unavailable macro fact changes deterministic candidate selection or score.

Use separate configuration/client defaults: `BITMAN_DART_EVENT_EVIDENCE_ENABLED=false`, connect 2 seconds, read 3 seconds, 15-minute cache, maximum five stocks, three structured calls plus one list call and two documents per stock. A 10-second monotonic whole-batch deadline is checked before/after every request and during bounded reads; deadline exhaustion or interruption stops further calls and is explicit. An already running request remains bounded by its HTTP timeout. Cache only valid success/normal empty (`013`); authentication (`010/011/012`) and quota (`020`) remain errors. Bound cache entries and one run's total call budget. Never log request URLs, key values or raw provider exceptions.

`DartApiService` exposes an additive batch-format method delegating to this service; preserve its existing constructors/methods. Root adds the selected-candidate evidence to orchestrator context at the already validated deterministic candidate boundary. Disabled mode must perform zero mapper/network requests.

## Original XML policy

Read compressed ZIP bytes through a bounded stream (1 MiB), bound expanded aggregate bytes (4 MiB), XML per-entry bytes (1 MiB), and entries (8). Reject unsafe/absolute/traversal entry names, no disk extraction. Harden XML against DOCTYPE, external entities, schemas and XInclude. Reject unsafe XML atomically; do not report truncated content as complete.

Extract only uniquely matched exact label/value rows for contract amount/period or ownership percentage/change, with units and receipt provenance. Duplicate/conflicting labels, multiple periods or ambiguous values remain unknown. Preserve bounded document evidence and explicit ambiguity; an amended filing always carries a correction warning and cannot earn an unqualified positive score. This parsing policy is a conservative implementation choice, not a claim that arbitrary DART original documents have one standard numeric schema.

## Fixture verification plan

Write behavior tests before production: disabled/no-key zero requests; five-stock/call bounds and caching; all three structured field contracts; identity/receipt mismatch, future dates and malformed amounts; normal empty versus auth/quota; document selection and amendment flags; ZIP oversize/entry/path rejection; DOCTYPE/external entities rejection; exact XML amount/ratio with preserved units; ambiguous XML unknown; prompt output and additive DartApiService integration. Use synthetic sanitized fixtures following official fields and in-memory ZIP/XML only. Run serialized offline Gradle RED/GREEN with root; no actual API calls, member data or secrets.
