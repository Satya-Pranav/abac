# Databricks Unity Catalog — governance architecture (where ABAC fits)

The big-picture context for this skill. Unity Catalog (UC) is Databricks' overarching governance
platform; the **row-filter / column-mask ABAC policies** this skill focuses on are one layer within
it. This note places that layer in the whole model so you know what ABAC does *not* cover (RBAC
grants, bindings, storage credentials) and how the layers compose at query time.

---

## 1. Governance architecture overview

Unity Catalog does not rely on a single monolithic model; it uses multiple coexisting security
frameworks that layer together to protect data.

### The core governance mechanisms

- **Role-Based Access Control (RBAC):** The foundational layer. Uses standard ANSI SQL
  (`GRANT`/`REVOKE`) to give explicit access to users or groups based on the object hierarchy
  (Catalog → Schema → Table/View/Volume).
- **Attribute-Based Access Control (ABAC):** The scalable metadata layer. Uses "Governed Tags"
  (e.g. `PII`, `Secret`) attached to data assets combined with central policies to dynamically
  evaluate access at runtime.
- **Row-Level Security (RLS) & Column Masking (CLS):** Granular, table-specific controls that use
  User-Defined Functions (UDFs) to filter rows (e.g. by region) or mask sensitive data (e.g.
  redacting SSNs) on the fly. **← this is exactly what this skill's row-filter / column-mask ABAC
  policies implement.**
- **Workspace–Catalog Bindings:** Environmental isolation boundaries that restrict specific catalogs
  or storage credentials to designated workspaces (e.g. ensuring production data cannot be touched
  from a development workspace).
- **Storage & Credential Governance:** Centralizes cloud IAM roles into "Storage Credentials" and
  "External Locations," abstracting raw cloud keys away from data analysts.

---

## 2. Key insights on ABAC & coexistence

### Coexistence rule

ABAC does not replace RBAC; they **colive** simultaneously.

- **Phase 1 (RBAC):** Unity Catalog first checks if a user has baseline structural permissions
  (e.g. `SELECT` on the table).
- **Phase 2 (ABAC):** If basic access is granted, the engine evaluates runtime tags and filters out
  rows or masks columns before returning the final query results.

> This is the same fact stated in `SKILL.md` and `databricks-policy-reference.md` from the policy
> side: **ABAC row-filter / column-mask policies never *grant* access — they only filter or mask what
> RBAC (`SELECT`) already allows.** No `SELECT` → nothing to filter.

### Beyond row/column filtering

While row filtering and column masking are the most prominent use cases for ABAC, it is expanding to
cover broader governance:

- **Dynamic Privilege Grants:** Applying execution rights dynamically based on data/asset tags. (These
  are ABAC **GRANT** policies — a *different* policy kind from the row-filter / column-mask policies
  this skill documents; `databricks-policy-reference.md` explicitly scopes itself to the latter.)
- **AI-Driven Data Classification:** Integrating with Databricks' autonomous classification systems to
  scan, tag, and immediately secure sensitive data fields via ABAC policies without manual
  configuration.

---

## 3. How this maps to the rest of the skill

| UC layer | Covered where |
|---|---|
| RLS / CLS via row-filter & column-mask ABAC policies | `SKILL.md` + `databricks-policy-reference.md` (semantics), `poc-playbook.md` (author/deploy/test) |
| Governed tags that drive those policies (`has_tag`) | `databricks-policy-reference.md` §4–5 |
| RBAC `GRANT` baseline the policies sit on top of | this doc §2 (coexistence) |
| ABAC GRANT policies, bindings, storage credentials | **out of scope** for this skill — noted here for context only |
