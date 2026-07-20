# Exploring Databricks ABAC behaviours — the sample program

Goal: poke the row filter from every angle and *watch* how Databricks reacts, so we
understand the model before writing formal test cases. The runnable harness is
[`sql/11_explore_behaviours.sql`](../../sql/11_explore_behaviours.sql). This doc is the map:
what each part shows, the RBAC_ABAC answer, and what's still missing in the dev env
(the agenda for the Meghana sync).

---

## How to run

Open `sql/11_explore_behaviours.sql` in the Databricks SQL editor **as the owner** and
run it top-to-bottom. It does **not** need OAuth, tokens, or the service principal —
it calls `abac_row_filter(entity, object_type, org_id, ctx)` directly with a **literal
`named_struct` context**. That struct is exactly what `get_user_context()` returns, so
the logic is identical, but you can sweep dozens of ctx values in seconds.

> **Why direct calls instead of querying the tables?** Owners *bypass* row-filter
> policies, so `SELECT * FROM customer` as you would show everything and teach you
> nothing. Calling the function directly sidesteps that — you see the raw allow/deny
> decision for any ctx you invent. The real OAuth path (Part E) is how you confirm the
> plumbing once the logic makes sense.

Each result set carries an `expected` column and a `verdict` (`ok` / `MISMATCH`), so
it self-checks. It seeds a namespaced synthetic fixture (`EXP_*`, `u.explore@…`) and
deletes exactly those rows in **Part Z** — your real seed is never touched. Run Part Z
even if you stop early.

---

## The four axes you wanted to explore, mapped to the harness

| Axis | Where | What you vary | What you learn |
| --- | --- | --- | --- |
| **ctx JSON values** | Part A | `mode`, `root`, `user`, `permissions` | the full allow/deny truth table for one place to look |
| **claims values** | Part A (body) + Part E | same JSON, but sent as a real OAuth `custom_claim` | that the claim reaches the filter unchanged |
| **row-filter logic** | Part 0 + A4 | — (you *read* what's deployed) | confirms the live filter is the full 3-branch (matches `sql/05`) |
| **metadata table values** | Part D | `isActive`, `isDeleted`, USER vs GROUP grants | which flags flip a row off, and which tables are even read |
| **RBAC_ABAC** | Part C | `mode=RBAC_ABAC`, org tree depth | how org-hierarchy visibility actually works (below) |

---

## `rbac_abac???` — answered

Three modes exist; they differ only in **how the *root* object type is filtered**:

- **`DISABLE`** — every row passes. Nothing else is evaluated. (Pipeline sanity switch.)
- **`ABAC`** — a root row shows only if there is an **explicit assignment** to `ctx.user`
  (directly as `USER_ID`, or via a `USER_GROUP` they belong to) in
  `ABAC_EntitySubjectAssignment`. Per-row, hand-picked.
- **`RBAC_ABAC`** — **additive**: a root row shows if its **org column is a child of `ctx.org`** in
  `orgHierarchy` **OR** the user has an explicit assignment (the `EXISTS` branch is not gated by
  mode — it's an `OR`). So it *adds* coarse "everyone in my org branch sees these rows" access **on
  top of** ABAC's per-row grants; it does not replace them.

The exact RBAC_ABAC test in the deployed filter is:

```sql
org_id IN (SELECT orgID FROM orgHierarchy WHERE parentOrgID = ctx.org AND isDeleted=false)
```

**Key finding (Part C proves it for THIS seed): the POC adjacency is a SINGLE LEVEL, not a subtree.**
`parentOrgID = ctx.org` matches only **direct children** (plus your own org, thanks to
the `('100','100')` self-loop row). A **grandchild is NOT visible** — the harness builds
`EXP_ROOT → EXP_CHILD → EXP_GRAND` and `EXP_GRAND` comes back `false`.

> **RESOLVED by the customer data** (`../../abac_docs/customer_data/`): the real `OrgHierarchy` is an
> **ancestor-closure** (each org paired with every ancestor incl. self + root), so the *same*
> `parentOrgID = ctx.org` predicate returns the **full subtree** — **no recursive walk needed**, the
> closure precomputes it. The single-level behavior here is purely our **adjacency seed**; to mirror
> production, seed each org → every ancestor. (So the customer *does* mean "whole subtree", and the
> deployed filter already delivers it against their closure table.)

Two more RBAC_ABAC facts the harness shows:
- RBAC_ABAC relaxes **only the root** type. Non-root tables still depend on the
  `permissions` branch; org membership does nothing for them.
- **Nothing currently sets `mode='RBAC_ABAC'`** — the seeded context and
  `get_test_user_context()` are both `ABAC`. So until now RBAC_ABAC has never actually
  run against real data. Part C is the first exercise of it.

---

## Which filter is live — settled: the full 3-branch

**Resolved.** The warehouse now runs the **full 3-branch** `abac_row_filter`, identical to
`sql/05_dataset_udfs.sql` (the customer template). The earlier ambiguity — repo said
3-branch, the runbook said the live warehouse was a simplified 2-branch — is gone: the
filter was redeployed, so repo and warehouse now **agree**. Both the `permissions` branch
and the RBAC_ABAC org branch are **live**.

The 3-branch OR is: `DISABLE` · `non-root & Item∈permissions` · `root & explicit/RBAC_ABAC`.
What the full 3-branch buys over the old 2-branch cut:
- the middle **`permissions`** branch → a non-root table shows its whole related table when
  its `Item` type is in `ctx.permissions` ("see the whole related table"), instead of only
  the `root` table ever returning rows.
- the **RBAC_ABAC** org branch → coarse org-subtree visibility on the root type, additive on
  top of ABAC's per-row grants.

**Part 0** prints `SHOW CREATE FUNCTION` so you can eyeball the live body, and **scenario A4**
is a quick sanity probe: it asks "is a non-root `Item` visible when `Item ∈ permissions`?" With
the 3-branch deployed the confirmed answer is **A4 = `true`** — the `permissions` branch is
live and non-root tables can show rows. (If A4 ever came back `false`, the old 2-branch had
sneaked back in; that is not the expected state.)

---

## Dev-environment checklist — what's missing to exercise all of this

Run Part 0 and Part A first; they answer most of these.

- [x] **Which `abac_row_filter` is live?** (Part 0 / A4.) **Resolved: the full 3-branch**
      (matches `sql/05_dataset_udfs.sql`). Repo and warehouse now agree; `permissions` and
      RBAC_ABAC are both live. A4 is expected to return rows.
- [ ] **Which `get_user_context()` is live?** No-OAuth table lookup (`04_helper_udfs.sql`)
      or the OAuth `current_oauth_custom_identity_claim()` body? Part 0 prints it.
- [ ] **Is `current_oauth_custom_identity_claim()` actually available** on the warehouse,
      and does the token endpoint accept `custom_claim` for our SP? (Needs the SP to have
      data-editor / OAuth-gen rights.) Confirm with a single Part E call.
- [ ] **Canonical testers = the dummy emails in `ABAC_EntitySubjectAssignment`**
      (`2012/u.analyst1`, `3006/u.vendor.mgr`, `118144/u.developer`) — driven via the service
      account by setting `claim.user`, or explored owner-direct from the UI. `03_seed_metadata.sql`
      now seeds exactly these three testers (the SP app id is used only for the no-OAuth
      `ABAC_UserContext` fallback). Run Step 0 in `jdbc-cases.md` to confirm the live seed matches.
- [ ] **No RBAC_ABAC context is seeded.** To exercise RBAC_ABAC on the *real* tables (not
      just the synthetic org tree), we need a principal/claim with `mode='RBAC_ABAC'` and an
      `orgHierarchy` populated from real `c_current_addr_sk` values (03 seeds 5 of these).
- [x] **Org tree depth — resolved.** Only a single level is seeded here (adjacency). The customer's
      real `OrgHierarchy` is an **ancestor-closure** (`../../abac_docs/customer_data/`), so the
      existing `parentOrgID = ctx.org` predicate already yields the **full subtree** — no recursive
      filter needed; to mirror production, seed a closure (org → every ancestor) instead of adjacency.
- [ ] **No group-grant ESA row on real data.** `test_group_1 → analyst` membership exists but
      no `USER_GROUP` assignment uses it, so the group path is untested on real tables.
- [ ] **Masking is stubbed.** `ABAC_AssignmentPermission` is populated but the row filter
      never reads it; `99_optional_masking.sql` is optional. Is column masking in scope?
- [ ] **`WORKSPACE_HOST` trailing slash** — must be `adb-….azuredatabricks.net` (no `/`).

---

## For the Meghana sync — questions to bring

1. **Row filter shape:** the POC now runs the **full 3-branch** filter (permission-gated
   related tables + RBAC_ABAC). Confirm **production intends the same** — i.e. prod parity —
   rather than an older per-root-only cut.
2. **RBAC_ABAC semantics:** direct-children-only (what's deployed) or a **true recursive
   subtree**? If subtree, we need the recursive filter.
3. **Identity source in prod:** OAuth `custom_claim` (Phase 2 here) or the table lookup? And
   is `claim.user` the end-user email or the SP app id in their real deployment? (Our exploration
   uses dummy emails as `claim.user` via the service account.)
4. **Confirm the live seed** matches `03_seed_metadata.sql` (now realigned to the dummy testers
   `u.analyst1/u.vendor.mgr/u.developer` + org tree) so a fresh reseed reproduces what's deployed.
5. **Scope:** is **column masking** part of this POC, or row filtering only?
6. **Env access:** confirm the SP's OAuth/`custom_claim` grant and the warehouse actually
   exposes `current_oauth_custom_identity_claim()`.

---

## Next

With the live filter settled as the full 3-branch, once Meghana confirms production parity
and the RBAC_ABAC intent, we turn the Part A / C / D grids into the formal matrix in
[`../deployment/oauth-jdbc-flow.md`](../deployment/oauth-jdbc-flow.md) §9 (allow / deny / DISABLE / RBAC_ABAC / flags),
and run each row through **both** the direct-call shortcut and the real OAuth path for parity.
