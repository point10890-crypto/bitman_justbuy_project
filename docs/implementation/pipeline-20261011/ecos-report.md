# ECOS context implementation and validation

Implemented a disabled-default, configurable three-series ECOS adapter with dedicated 2s connect/3s read transport, ten-second batch budget, fixed official host, bounded response parsing, successful two-hour cache and explicit short error cooldown. Missing keys make no request. Daily/monthly periods, actual units/source, retrieved time and unavailable release time are retained. Wrong identity/unit/dimensions, malformed/nonfinite values, future or stale observations are excluded.

The existing MarketDataService appends the processed ECOS context to real agent input. Root orchestrator separately passes at most five deterministic candidates (explicit query first) to KIS/DART enrichment. Evidence cannot originate new candidates, and optional source failure leaves the other source and price publication gate intact.

Verification: initial ECOS 16 cases / 12 intended failures; implementation passed all 16. Independent review then reproduced two object/array dimension acceptance failures and a monthly invented-day failure; those three were fixed. Final selective run passed ECOS 19/19 plus SourceEvidenceIntegration 4/4, within a combined 47/47 GREEN run. HTTP fixtures only; no account key or actual credential probe. Full Gate B and production release are handled by the coordinator.

Official public sample metadata and response format checks are documented in ecos-design.md and expansion-brief.md. They validate the contract, not current market values. The user has no ECOS key yet, so BITMAN_ECOS_ENABLED stays false. Obtain a server-side ECOS_API_KEY before activating and validating actual observation coverage. KIS commercial/member-service rights are also still pending.
