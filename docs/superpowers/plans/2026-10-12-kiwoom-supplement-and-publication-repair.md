# KIS primary, Kiwoom supplement and publication repair

User selection: keep KIS as primary and use Kiwoom only for missing facts. The current swing/leader publishing issue must be diagnosed and fixed independently of provider migration. Preserve unrelated files, members, subscriptions, credentials and existing Telegram destinations.

## Evidence and scope

- 08:50: both REVERSAL_EDGE and FLOW_LEADER had ChatGPT/DeepSeek response success but no new publication. Prior cache rows lack publication provenance and cannot be restored as verified picks.
- FLOW_LEADER required unavailable Grok search instead of accepting supplied KIS evidence. REVERSAL_EDGE also described an unavailable Grok-dependent combination.
- Publication counts and schema rejection reasons were absent. Add diagnostics without retaining provider responses, identifiers or sensitive data.
- Kiwoom supplies verified quote/valuation, completed daily-chart facts and historical investor facts for existing verified candidate codes. Do not add order, account or trading operations, create candidates with an LLM, overwrite verified KIS facts, or copy proprietary sample implementations.
- New Kiwoom operations default disabled and unlicensed. The user's issued BitMan key is entered directly in the local ignored backend environment file. No credentials from other apps.

## Implementation and verification

- [x] Add schema inspection and publication-stage counters; retain strict parsing and price/provenance requirements.
- [x] Remove agent-specific mandatory data dependencies in swing/leader mode instructions and test publication with verified candidates.
- [x] Add four-TR closed Kiwoom query adapter, bounded transport, private token cache, global throttle and explicit timestamps/units/coverage.
- [x] Request only missing primary evidence; use auxiliary quotes only when primary publication quote verification fails.
- [x] Expose supplement readiness and source status without credentials. Keep KIS metadata independent and preserve API compatibility.
- [x] Full backend 756 tests passed (95 suites, no failures/errors/skips); frontend 198 tests passed and TypeScript/Vite build passed. The final commit includes scoped source/tests, matching Spring static assets and these verification notes.
- [x] Deploy MiniPC, verify JAR equality, local/public health and exact homepage. Both Cloudflare Pages aliases serve the new bundle and SW r8; the admin shows the selected MOCK configuration.
- [x] Verify swing/leader from startup recovery or silent admin refresh, then record actual candidate/schema/quote/publication counts. Final swing run published and saved 2 verified picks despite both overlapping the short-term cache; duplicate notification alone was skipped. The exact homepage displays swing 2 and leader 3. Recovery 4/4 includes one new AI mode and three cache hits.

Activation requires actual permitted production use and the MiniPC's public outbound IP registered in Kiwoom. Issued keys alone do not prove those conditions. The adapter follows the official protocol independently; DART/ECOS and KIS remain in place.

User-entered BitMan keys were installed in the same two MiniPC environment fields without changing other runtime settings. The real-server token request was rejected with detailed code 8030 (real/mock key mismatch). The user explicitly chose MOCK, so both environments now select the official mock host; a MiniPC mock token request succeeded (return_code=0, token received). No market data was requested. Retain disabled operation/rights flags and exclude MOCK evidence/quotes from the member-facing actual-data pipeline.

Additional review repairs: shared startup/cron/admin execution lock, cached-quote session/180-second revalidation, completed-attempt metadata on homepage empty states, date-aware old snapshots, one-mode admin refresh, and excluding normal EMPTY from failed-mode retry. Independent realtime scanner rows do not inherit the last scheduled AI empty message.

Live 10:51 evidence found a second publishing defect: verified swing picks (2) were erased by cross-mode dedup against the earlier short-term cache. Preserve verified publication independently per strategy and deduplicate only Telegram delivery copies. Same-mode/ETF/identity/price filters remain. Two startup/cron regressions reproduce the missing save before the change; verify no duplicate notification and independent mode tracking afterward.
