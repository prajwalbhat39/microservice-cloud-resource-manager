# Automated test suite — summary

Captured 2026-10-20 (UTC) on the committed source. Full verbatim Maven output is in
`quota-tests.txt` and `resource-tests.txt` alongside this file.

| Service | Unit tests (surefire, `*Test`) | Integration tests (failsafe, `*IT`, Testcontainers) | Total | Failures |
|---------|-------------------------------:|----------------------------------------------------:|------:|---------:|
| Quota | 6 | 7 | 13 | 0 |
| Resource / orchestrator | 33 | 12 | 45 | 0 |
| **Total** | **39** | **19** | **58** | **0** |

Both builds reported `BUILD SUCCESS`.

## Which test proves which rule

### Quota service
| Test | Kind | Proves |
|------|------|--------|
| `TierPolicyTest` | unit | BR8 (FREE may not provision CLUSTER), INVALID_TIER parsing |
| `Br1ConcurrencyIT` | Testcontainers | BR1 (atomic reservation under a genuine 20-way concurrent race) |
| `Br7ReleaseExactlyOnceIT` | Testcontainers | BR7 (release is exactly-once, incl. the concurrent case) |

### Resource / orchestrator service
| Test | Kind | Proves |
|------|------|--------|
| `RequestFingerprintTest` | unit | BR3 (payload fingerprinting; canonicalisation) |
| `ResourceLifecycleTest` | unit | BR4 (only defined transitions; ACTIVE only from PROVISIONING) |
| `ClientTokenTest` | unit | FR-19 (stable client token across retries) |
| `IdempotencyServiceTest` | unit | BR2 (the decision table: fresh / replay / in-flight / mismatch) |
| `IdempotencyReplayTest` | unit | the verbatim-replay rule (original correlationId/timestamp preserved) |
| `ProvisioningSagaCompensationTest` | unit (WireMock) | BR6 (reverse-order compensation; only completed steps undone) |
| `IdempotencyConcurrencyIT` | Testcontainers | BR2 under a real 10-way concurrent duplicate race |
| `IdempotencyPayloadMismatchIT` | Testcontainers | BR3 against a real database; peer-rejection replay |
| `TerminationIdempotencyIT` | Testcontainers | BR7 gate 1 (one winner under concurrent termination) |

## Requirement mapping

- **TR-2** (BR4, BR5, BR6, BR8 are unit tests with no DB/network): satisfied for BR4, BR6, BR8.
  BR5 is the Billing service's rule and is not in U1.
- **TR-3** (BR1, BR3, BR7 are database-backed Testcontainers tests): satisfied — `Br1ConcurrencyIT`,
  `IdempotencyPayloadMismatchIT`, `Br7ReleaseExactlyOnceIT` / `TerminationIdempotencyIT`.
- **TR-4** (BR1 test includes a genuinely concurrent case): satisfied — `Br1ConcurrencyIT` fires
  20 reservations in parallel.
- **TR-5** (BR2/BR3 include a concurrent duplicate case): satisfied — `IdempotencyConcurrencyIT`.
