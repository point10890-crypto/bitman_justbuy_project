# ECOS market context design

The official public `sample` StatisticSearch responses were checked on 2026-10-11 for the three table/item combinations in expansion-brief.md. They contain STAT_CODE, ITEM_CODE1, UNIT_NAME, TIME and DATA_VALUE, but no release timestamp. These public sample responses were contract checks, not current market evidence or a user's credential verification.

Use a separate 2s connect / 3s read client, up to three configured series, a 10s monotonic batch budget, a two-hour successful cache capped at the Korean date boundary, and a short explicit error cooldown. Enabled is false by default; a missing/invalid key makes no HTTP call. Pin requests to https://ecos.bok.or.kr. Invalid series/cycle, wrong identity/unit, extra dimensions, malformed/nonfinite values, future periods and old observations stay unavailable. Retain observed period, source, unit and fetchedAt; releasedAt remains null. Daily and monthly periods are explicitly distinct.

Append the bounded formatted context into MarketDataService's existing prompt input through an optional setter so existing constructors remain compatible. The text identifies 한국은행 ECOS as the source and says the values are processed published statistics, not live stock quotes. No scoring, candidate creation, order or simulated value is added.

Fixture coverage: disabled/missing key no-call, valid latest observations and provenance, identity/unit/dimension errors, malformed/future/stale periods, nonfinite data, provider failure with no credential leak, bounded configured series/caching and actual market-context integration.

Official references: [ECOS OpenAPI](https://ecos.bok.or.kr/api/), [BOK information use](https://www.bok.or.kr/portal/main/contents.do?menuNo=200228). Runtime activation requires the user's ECOS_API_KEY and BITMAN_ECOS_ENABLED=true after verification.
