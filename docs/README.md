# `docs/` — ABAC POC documentation index

Operational docs for the ABAC-on-Databricks POC. The **root [`../README.md`](../README.md)** is the
master: architecture, the customer design, the TPC-DS mapping, and the `sql/` execution plan. This
folder holds the deployment, testing, and historical material; the reusable Databricks ABAC semantics
live in the **skill**.

## Reusable semantics → the skill

The Databricks Unity Catalog ABAC reference (the e6data planner-side view) and the POC playbook are
packaged as a project skill:

- **[`../.claude/skills/databricks-abac/SKILL.md`](../.claude/skills/databricks-abac/SKILL.md)** — when-to-use + quick reference + gotchas.
  - `references/databricks-policy-reference.md` — doc-verified `CREATE POLICY` grammar, binding, tag inheritance, conflicts, limits, fail-closed.
  - `references/poc-playbook.md` — author → deploy → test one row filter end to end.

## Deployment

| Doc | What it covers |
|---|---|
| [`deployment/oauth-jdbc-flow.md`](deployment/oauth-jdbc-flow.md) | OAuth M2M + `custom_claim` injection, the JDBC client token hot-swap, the query lifecycle, correctness gotchas (the active model) |
| [`deployment/runbook.md`](deployment/runbook.md) | End-to-end OAuth runbook: policies, functions, metadata, env, mint the token, run the client. **Secrets are placeholders — never commit real values.** |

## Testing

| Doc | What it covers |
|---|---|
| [`testing/jdbc-cases.md`](testing/jdbc-cases.md) | The **60-case + 8-scenario** catalog with per-row filter trace: how to run, the tester model, the deployed 3-branch filter, groups A/B/R/C/T-O/M/TH/conflict/DR/V/SC/TG/UC/XT/CL, the DR2 + e6data scenarios, and a one-line summary per case. Groups V/SC/TG/UC/XT are written but **not yet verified live** (`sql/16`–`20` pending apply) |
| [`testing/explore-behaviours.md`](testing/explore-behaviours.md) | Owner-side behaviour sweep (`sql/11`) — ctx/claim/mode/metadata grids, RBAC_ABAC, which filter is live |

## Design & findings

| Doc | What it covers |
|---|---|
| [`superpowers/specs/2026-07-20-abac-multi-engine-test-suite-design.md`](superpowers/specs/2026-07-20-abac-multi-engine-test-suite-design.md) | Approved design for the 3-phase expansion: close the Databricks coverage gaps (`sql/16`–`20`), make the suite engine-pluggable (Engine SPI + capability gating), add e6data scenario cases |
| [`superpowers/plans/2026-07-20-abac-multi-engine-test-suite.md`](superpowers/plans/2026-07-20-abac-multi-engine-test-suite.md) | The 14-task implementation plan for that design — refactor first behind a byte-identical-output gate, then `sql/16`–`20` and the new case groups |
| [`e6data-defect-report.md`](e6data-defect-report.md) | Findings from a source read of `e6-query-engine` / `e6-jdbc-driver` — fail-open governance gaps, cluster/failover issues, hygiene. **Unverified by execution — confirm before filing.** |

## Archive

| Doc | Status |
|---|---|
| [`archive/abac-tpcds-setup-plan.md`](archive/abac-tpcds-setup-plan.md) | ⚠️ **Superseded** earlier draft plan (written before the real customer code). Kept for provenance only — see root README §8 for the corrections. |

## Not in `docs/`

- **`../abac_docs/`** — the authoritative real customer artifacts (Databricks SQL templates, Sentinel migration scripts, the Java app layer, and **`customer_data/`** — the real metadata-table DDLs + sample data & per-tenant scale estimates, see [`../abac_docs/customer_data/README.md`](../abac_docs/customer_data/README.md)). Source of truth.
- **`../sql/`** — the runnable execution plan (`00`–`20` + `99`).
- **`../JDBC/`** — the JDBC client + the `Runner` test suite (engine-pluggable); see its own `README.md`.
