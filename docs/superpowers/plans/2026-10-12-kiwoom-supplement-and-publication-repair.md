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
- [x] Full backend 745 tests passed (95 suites, no failures/errors/skips); frontend 198 tests passed and TypeScript/Vite build passed. Commit/push follows verification.
- [ ] Deploy MiniPC, verify JAR equality, local/public health and exact homepage.
- [ ] Verify swing/leader from startup recovery or silent admin refresh, then record actual candidate/schema/quote/publication counts. Avoid repeating an active or already completed run. Do not force publish a result with insufficient source evidence.

Activation requires actual permitted production use and the MiniPC's public outbound IP registered in Kiwoom. Issued keys alone do not prove those conditions. The adapter follows the official protocol independently; DART/ECOS and KIS remain in place.

User-entered BitMan keys were installed in the same two MiniPC environment fields without changing other runtime settings. The real-server token request was rejected with detailed code 8030 (real/mock key mismatch); request no market data and keep the supplement disabled. The user must provide the appropriate real REST keys and confirm permitted usage.

Additional review repairs: shared startup/cron/admin execution lock, cached-quote session/180-second revalidation, completed-attempt metadata on homepage empty states, date-aware old snapshots, one-mode admin refresh, and excluding normal EMPTY from failed-mode retry. Independent realtime scanner rows do not inherit the last scheduled AI empty message.
