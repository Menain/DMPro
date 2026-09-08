# Architecture Decision Records

> CloudDM 数据库变更治理平台二次开发（P0）· 重大架构决策记录
>
> Source spec: `docs/spark/2026-09-06-clouddm-db-change-governance-design.md` (rev.2.1)
> Delivery map: `docs/governance/phase-delivery-map.md`
> Domain contracts: `.trellis/spec/backend/governance-contracts.md`

## Index

| ADR | Topic | Status | Spec section | Impl. Phase(s) |
|---|---|---|---|---|
| [ADR-001](ADR-001.md) | Role/Group separation + materialized expansion + ledger + res_desc marker + revoke protection | Accepted | §3.2 / §5 | P2 (P1 schema) |
| [ADR-002](ADR-002.md) | Reuse dm_approval + thin governance layer weak coupling + 7 touchpoint budget | Accepted | §2.2 / §3.1 | P2, P4, P5, P7, P9 |
| [ADR-003](ADR-003.md) | Dual-source gate model + path B DML-only constraint package | Accepted (correction: threshold moved to submit-time) | §4.1 / §4.4 | P6, P7, P8 |
| [ADR-004](ADR-004.md) | Dialect-neutral hash contract: SHA256(original + deterministic whitespace normalization) | Accepted | D10 / §3.4 | P4 (P6, P7 reuse) |
| [ADR-005](ADR-005.md) | Governance roles via env param (GOV_ROLE), no dm_sys_env change, per-env gray release | Accepted (no-UI-editor confirmed intentional) | §3.3 / §8.3 | P1, P3 |
| [ADR-006](ADR-006.md) | Production execution dual-control + GOV_AUTO_CONFIRM + SYSTEM directed channel | Accepted | §4.2 / D12 | P4, P7 |
| [ADR-007](ADR-007.md) | PROD approval provider: mandatory third-party, no Internal self-approval | Accepted | §5.5 | P6, P9 |
| [ADR-008](ADR-008.md) | Guard dual entry points + scan-based self-healing promoter, no state-funnel invasion | Accepted (correction: #3 disposal = doDeleteJob not markJobFailedIfActive) | §4.4 / §4.5 | P4, P7 |
| [ADR-009](ADR-009.md) | Statement-level versioning + correction loop (three-tier model) + PROD no in-place correction | Accepted (touchpoint #6 locked to replaceTask) | §3.4 / §4.2 / §4.6 | P4, P5 |
| [ADR-010](ADR-010.md) | Execution config component routing: mixed tickets, no forced split, no SKIP | Accepted | D15 / §4.6 | P4, P7 |

## Status legend

- **Accepted**: decision is finalized and implemented in the codebase.
- ADRs marked with a correction note reflect implementation-time refinements where the spec's design was structurally adjusted during delivery (e.g. threshold tiering relocation, disposal method change). The decision status remains Accepted; the correction and its rationale are documented in the ADR body.

## Cross-references

- **Spec decisions**: `docs/spark/2026-09-06-clouddm-db-change-governance-design.md` §0 (D1-D16 decision log)
- **Phase delivery**: `docs/governance/phase-delivery-map.md` (13-phase commit hashes + §8.2 acceptance mapping)
- **Domain contracts**: `.trellis/spec/backend/governance-contracts.md` (executable contracts verified against code)
- **Rollout runbook**: `docs/governance/rollout-runbook.md` (gray-release operations)
- **Real-env verification**: `tests/governance/prod-verification.md` (manual verification checklist)
- **Test coverage matrix**: `.trellis/tasks/archive/2026-09/09-06-gov-phase11-testing/research/coverage-matrix.md` (54 items: 43 FULL + 11 EXEMPT, 411 @Test methods)
