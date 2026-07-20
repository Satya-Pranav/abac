# Customer metadata tables — real schemas, sample data & scale

Real DDL + sample rows for the five ABAC metadata tables the row filter / column mask read, pulled
from the customer's environment. **This is the authoritative physical schema** — where our POC's
simplified metadata tables (`sql/02_metadata_tables.sql`) or the docs disagree, this wins.

Contents of this folder:

| File | What it is |
|---|---|
| `ABAC_Assignment.rtf` | real `CREATE TABLE` DDL |
| `ABAC_EntitySubjectAssignment.rtf` | real `CREATE TABLE` DDL |
| `ABAC_AssignmentPermission.rtf` | real `CREATE TABLE` DDL |
| `UserGroupMembers.rtf` | real `CREATE TABLE` DDL |
| `OrgHierarchy.rtf` | real DDL — a **VIEW** over `OrgHierarchyBase` |
| `ABAC related tables sample data & estimates.xlsx` | one sheet per table: sample rows + a per-tenant row-count estimate |

---

## 1. Real physical schemas

All tables carry audit/timestamp columns (`eventTime`, `recModifiedTime`, `createDT`/`updateDT`,
sometimes `createdBy`/`updatedBy`) and a **`tenantHash`** column. The row filter only reads a subset
(shown **bold**).

**`ABAC_Assignment`** — a role grant (e.g. *Owner*, *Viewer*, *Internal Owner*) on an object type.
Partitioned by `objectType`.
```
id long              -- ** the assignment id (a LONG, not a string); esa.assignmentID joins here
guid string
staticIdentifier string   -- e.g. 'owner', 'viewer', 'internal-owner'
name string               -- e.g. 'Owner', 'Viewer'
objectType string         -- partition key: 'Contract', 'Engagement', 'Assessment', 'Asset', ...
sourceType string         -- e.g. 'SYSTEM'
isActive boolean     -- ** filter requires a.isActive
createDT/updateDT/eventTime/recModifiedTime timestamp    (sample also has createdBy/updatedBy)
tenantHash string
isDeleted boolean    -- ** filter requires a.isDeleted = false
```

**`ABAC_EntitySubjectAssignment`** (ESA) — maps a subject (user or group) to an entity. Partitioned
by `objectType`.
```
assignmentId long    -- ** joins ABAC_Assignment.id
policyId long             -- NOT read by the row filter
entityId string      -- ** the entity key (a UUID in real data); filter: esa.entityID = entity_id
entityOrganizationId string   -- the entity's org (a UUID); NOT read by the filter (org comes from the tagged table column)
subjectId string     -- ** the granted subject: a USER_ID (user UUID) or USER_GROUP (group UUID)
subjectType string   -- ** 'USER_ID' | 'USER_GROUP'
objectType string    -- ** partition key: 'Contract' / 'Engagement' / ...
updateDT/eventTime/recModifiedTime timestamp
tenantHash string
isDeleted boolean    -- ** filter requires esa.isDeleted = false
```
> Note there is **no `isActive` on ESA** — the active/deleted switches live on the joined
> `ABAC_Assignment` (`a.isActive`, `a.isDeleted`).

**`ABAC_AssignmentPermission`** — permission names attached to an assignment (used by the **column
mask**, not the row filter).
```
assignmentId long    -- joins ABAC_Assignment.id
name string          -- e.g. 'contracts.fields.basic.view', 'contracts.fields.advanced.view'
createDT/updateDT/eventTime/recModifiedTime timestamp
tenantHash string
isDeleted boolean
```
> The mask's hierarchy trick lives here: `replace(ap.name, '.advanced.', '.basic.') = permission`,
> so an `.advanced.` grant satisfies a `.basic.` request.

**`UserGroupMembers`** — group membership.
```
memberId string      -- ** the user (UUID); filter joins ugm.memberID = ctx.user
groupId string       -- ** the group (UUID); filter joins esa.subjectID = ugm.groupID
eventTime/recModifiedTime timestamp
isDeleted boolean    -- ** filter requires ugm.isDeleted = false
tenantHash string
```

**`OrgHierarchy`** — a **VIEW**, not a table:
```sql
CREATE OR REPLACE VIEW OrgHierarchy AS
  SELECT * FROM OrgHierarchyBase WHERE isDeleted IS NOT TRUE;   -- keeps false AND null
```
`OrgHierarchyBase`:
```
rootOrgId string, rootOrgName string      -- the subtree root (same across a tenant's rows)
orgId string, orgName string        -- ** the org; filter selects orgID
parentOrgId string, parentOrgName string  -- ** filter: WHERE parentOrgID = ctx.org
eventTime/recModifiedTime timestamp
isDeleted boolean    -- ** filter also adds isDeleted = false (view already excludes deleted)
tenantHash string
```

---

## 2. Per-tenant row-count estimates (scale)

| Table | Rows / tenant | Note |
|---|---:|---|
| `ABAC_EntitySubjectAssignment` | **~600M – 1B** | the big one — the row filter's `EXISTS` scans this per row |
| `ABAC_AssignmentPermission` | ~5M | mask only |
| `ABAC_Assignment` | ~500K | |
| `UserGroupMembers` | ~100K | |
| `OrgHierarchy` | ~100K | |

**Planner-side implication (e6data):** the row filter runs an `EXISTS` sub-query against a
**~1B-row** ESA *for every row of the governed table*. Both ESA and `ABAC_Assignment` are
**partitioned by `objectType`**, so `esa.objectType = object_type` is a partition prune — the single
most important optimization. `entityID` / `subjectID` / `assignmentID` lookups within a partition are
the next hot path. This is the cost the planner must push down / cache, not evaluate naively per row.

---

## 3. Corrections these tables force on the docs

1. **The RBAC_ABAC org branch is FULL-SUBTREE in reality, not single-level.** The `OrgHierarchy`
   sample shows each `orgId` paired with **many** `parentOrgId` values — *including itself and the
   root org*. That is a denormalized **ancestor-closure** (one row per (org, ancestor) pair), not a
   parent→child adjacency list. So `WHERE parentOrgID = ctx.org` returns **every org whose ancestor
   set contains `ctx.org`** = the whole subtree rooted at `ctx.org` (inclusive). The customer comment
   *"show everything in the org tree"* is therefore **accurate**.
   - Our **POC** seeds `orgHierarchy` as a simple parent→child **adjacency list**, so the identical
     `parentOrgID = ctx.org` predicate behaves **single-level** (grandchildren excluded — see the
     grandchild test in `docs/testing/jdbc-cases.md`). That single-level behavior is an artifact of
     the POC seed, **not** of the filter. To reproduce real subtree behavior in the POC, seed
     `orgHierarchy` as a closure (org → every ancestor incl. self + root).

2. **Real ids are UUIDs, not emails or integer surrogate keys.** `subjectId`, `entityId`, `orgId`,
   `parentOrgId`, `groupId`, `memberId`, `entityOrganizationId` are all UUID strings;
   `ABAC_Assignment.id` is a `long`. So the real `ctx.user` = a **user UUID** (matched against
   `subjectId`). Our POC substitutes readable dummy emails (`u.analyst1@example.com`) and TPC-DS
   integer surrogate keys purely for legibility — the mechanism is identical.

3. **`tenantHash` is the real tenant isolation — confirming `ctx.tenant` is inert in the filter.**
   Every table has a `tenantHash`; tenancy is enforced by scoping to it (per-tenant data/schema), not
   by the row filter reading `ctx.tenant` (which it never does). This corroborates the T-group finding
   that `ctx.tenant` provides no row isolation.

4. **Column-name casing is cosmetic.** The physical columns are camelCase (`entityId`,
   `assignmentId`, `orgId`, `parentOrgId`, `memberId`, `groupId`); the customer's own
   `create_row_filter.sql` references them as `entityID`, `assignmentID`, `orgID`, `parentOrgID`,
   `memberID`, `groupID`. Spark SQL is **case-insensitive** on identifiers, so they resolve — no bug.

5. **ESA carries `entityOrganizationId` and `policyId` that the row filter ignores.** Org for the
   RBAC branch comes from the **tagged table column** (`org_id`), not `esa.entityOrganizationId`; and
   `policyId` links to a policy the filter doesn't consult.

---

## 4. Sample rows (illustrative; test-tenant synthetic UUIDs)

```
ABAC_Assignment
  id=177 staticIdentifier=owner  name=Owner         objectType=Contract    sourceType=SYSTEM isActive=1 isDeleted=0
  id=176 staticIdentifier=viewer name=Viewer        objectType=Engagement  isActive=0 isDeleted=0
  id=164 staticIdentifier=internal-owner name='Internal Owner' objectType=Engagement isActive=1 isDeleted=1

ABAC_EntitySubjectAssignment   (assignmentId, entityId, subjectId, subjectType, objectType)
  177 | 691ce46b-…  | 01405087-… | USER_ID | Engagement
  164 | 97dec1b2-…  | 4b529cfc-… | USER_ID | Engagement
  162 | 9c114bc2-…  | 7e4d0cd9-… | USER_ID | Asset

ABAC_AssignmentPermission   (assignmentId, name)
  162 | contracts.fields.advanced.view
  162 | contracts.fields.basic.view
  176 | engagements.fields.basic.view

UserGroupMembers   (memberId, groupId)
  670192eb-… | 02086ec7-…
  22410e29-… | 02d122b9-…

OrgHierarchy   (orgId 'Reorder002' appears with MANY parentOrgId — the ancestor-closure)
  rootOrg=Test_root_org_kafka  org=Reorder002  parent=Reorder002          (self)
  rootOrg=Test_root_org_kafka  org=Reorder002  parent=Test_root_org_kafka (root)
  rootOrg=Test_root_org_kafka  org=Reorder002  parent='Test ORG Sub1'
  rootOrg=Test_root_org_kafka  org='new 1'     parent='new 1' / Test_root_org_kafka   (depth-1: self + root only)
```

---

## 5. How the POC maps onto this

- POC schema = a **subset** of the above (`sql/02_metadata_tables.sql`): the bold columns only,
  minus `tenantHash` / `policyId` / `entityOrganizationId` / audit timestamps, and `orgHierarchy` as
  a plain table (adjacency) rather than a view over a closure.
- POC `subjectID` / `ctx.user` = dummy emails; real = user UUIDs. POC `entityID` = TPC-DS surrogate
  keys; real = entity UUIDs.
- Everything the row filter does is identical; only the data shape and scale differ. See
  `.claude/skills/databricks-abac/references/poc-playbook.md` for the deployed filter and
  `docs/testing/jdbc-cases.md` for the test cases.
