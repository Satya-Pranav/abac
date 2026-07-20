package com.abacpoc.cases;

import com.abacpoc.engine.Capability;
import com.abacpoc.engine.Engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The catalog of ABAC test cases plus the shared claim/constant helpers they're built from. */
public final class Cases {

    // Table used by case W1 (two conflicting row-filter policies — see JDBC_CASES.md).
    public static final String CONFLICT_TABLE = "warehouse";

    // Cases TH1-TH3 query the real `inventory` table (sql/14): a separate row filter whose 3b uses
    // '>=' instead of '=', so an assignment of value 500 shows every row with inv_quantity_on_hand
    // >= 500. Requires sql/14 applied first.
    public static final String THRESH_TABLE = "inventory";

    // Cases DR1/DR2 (sql/15). DR1 = CLASSIC RLS on `reason` (ALTER TABLE ... SET ROW FILTER; no tags,
    // no policy) — a normal Case. DR2 = the ABAC has_tag() POLICY flow on `income_band` (20 fixed rows,
    // ib_income_band_sk 1..20): a stateful hot-swap the suite drives — assert baseline, CREATE OR
    // REPLACE the SP-owned inner UDF (dr2_row_filter), wait 10s, re-assert, then revert.
    public static final String DR2_TBL = "income_band";
    public static final String DR2_FN  = "dr2_row_filter";

    // Cases WP1/WP2 (web_page) + WS1 (web_site) query the two ROW-FILTER conflict tables (sql/12).
    // Cases N1-N4 query the newly-onboarded governed tables promotion/store/call_center/ship_mode (sql/13),
    // and ODEL/OLIVE use the DEL_ORG/LIVE_ORG orgHierarchy rows from sql/13. All require their UI setup
    // applied first; otherwise those cases ERROR (table / policy / metadata not present).

    // DISABLE claim used only to measure a table's TOTAL rows (for ALL expectations).
    public static final String DISABLE_CLAIM =
        "{\"tenant\":1,\"user\":\"probe\",\"org\":\"100\",\"mode\":\"DISABLE\",\"root\":\"Customer\",\"permissions\":[]}";

    // ---- Self-seeding fixture (namespaced; inserted at start, dropped at end) ----
    // Uses REAL entity ids (2012/3006/118144) + REAL dummy emails, but via suite-only assignment
    // ids ('suite_a_*') and a suite-only org parent ('SUITE_ORG'), so teardown removes ONLY the
    // suite's rows and never the real seed. If the row already exists in the real seed, the extra
    // suite row is harmless (EXISTS just needs >=1). Requires the SP to have MODIFY on the metadata
    // tables (see sql/09); if it doesn't, the suite skips seeding and uses whatever is already there.
    public static final String SUITE_ORG = "SUITE_ORG";       // RBAC parent org the fixture populates
    public static final String SUITE_EMPTY = "SUITE_EMPTY";   // an org with no children (used by R2)

    private Cases() { }

    public static String claim(String user, String org, String mode, String root, String permsJsonArray) {
        return claim(1L, user, org, mode, root, permsJsonArray);
    }

    /** Same, but lets a case set an explicit tenant (default overload uses tenant=1). */
    public static String claim(long tenant, String user, String org, String mode, String root, String permsJsonArray) {
        return "{\"tenant\":" + tenant + ",\"user\":\"" + user + "\",\"org\":\"" + org + "\",\"mode\":\"" + mode
             + "\",\"root\":\"" + root + "\",\"permissions\":" + permsJsonArray + "}";
    }

    public static List<Case> all(Engine e) {
        List<Case> cs = new ArrayList<>();

        // ---- A. Pure ABAC — per-root explicit assignment (branch 3b EXISTS) ----
        cs.add(new Case("A1", "ABAC", "DISABLE -> branch 1 fires; show all rows, identity ignored.",
            "START: the app mints an OAuth token whose custom_claim sets mode=DISABLE (user/root present but ignored) and hot-swaps it into the SP session. FILTER: on each customer row UC calls abac_row_filter(c_customer_sk,'Customer',c_current_addr_sk,ctx); branch 1 'ctx.mode=DISABLE' short-circuits TRUE before identity is ever read. END: every customer row is returned, so the count equals the table total — DISABLE is the master allow-all switch.",
            claim("u.analyst1@example.com", "100", "DISABLE", "Customer", "[]"),
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.all()));
        cs.add(new Case("A2", "ABAC", "Baseline: branch 3b EXISTS matches analyst -> Customer entity 2012.",
            "START: inject the analyst claim (user=u.analyst1@example.com, mode=ABAC, root=Customer, no permissions). FILTER: per customer row, branch 1 no; branch 2 needs root<>object_type ('Customer'='Customer') no; branch 3 gate opens, 3a needs RBAC_ABAC so false, 3b EXISTS looks for an active, non-deleted assignment of THIS c_customer_sk to the analyst — only entity 2012 has one. END: exactly 1 row (2012) — the canonical per-row ABAC grant.",
            claim("u.analyst1@example.com", "100", "ABAC", "Customer", "[]"),
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.exact(1)));
        cs.add(new Case("A3", "ABAC", "The visible id list is exactly [2012] (asserted; observed run: [2012]).",
            "START/FILTER: identical claim and 3b evaluation as A2, but the query projects c_customer_sk instead of count(*). END: the visible id list is ASSERTED to equal exactly [2012] — proving the analyst sees precisely their assigned entity and no other id leaks (observed run returned [2012]).",
            claim("u.analyst1@example.com", "100", "ABAC", "Customer", "[]"),
            "SELECT c_customer_sk FROM " + e.qualify("customer") + " ORDER BY 1", Expect.exactIds("2012")));
        cs.add(new Case("A4", "ABAC", "Item tester -> Item entity 3006 (same mechanism, different root).",
            "START: inject the vendor-manager claim (user=u.vendor.mgr@example.com, root=Item). FILTER: on item the branch 3 gate opens (root='Item'=object_type), 3b EXISTS matches the single Item assignment for entity 3006 to that user. END: 1 row — the same 3b mechanism as A2 on a different root and table.",
            claim("u.vendor.mgr@example.com", "100", "ABAC", "Item", "[]"),
            "SELECT count(*) FROM " + e.qualify("item"), Expect.exact(1)));
        cs.add(new Case("A5", "ABAC", "StoreSale tester -> all sales with ss_customer_sk = 118144.",
            "START: inject the developer claim (root=StoreSale). FILTER: on store_sales, 3b EXISTS matches every sales row whose ss_customer_sk = 118144 (the assigned entity id). END: >0 rows — one assignment can grant many physical rows that share the same entity id.",
            claim("u.developer@example.com", "100", "ABAC", "StoreSale", "[]"),
            "SELECT count(*) FROM " + e.qualify("store_sales"), Expect.nonzero()));
        cs.add(new Case("A6", "ABAC", "Deny wrong user: Item tester has no Customer assignment.",
            "START: inject the vendor-manager claim but with root=Customer. FILTER: the gate opens, but 3b finds no Customer assignment for u.vendor.mgr (his grant is on Item, not Customer). END: 0 rows — assignments are scoped to (object type + user), so the wrong user is denied.",
            claim("u.vendor.mgr@example.com", "100", "ABAC", "Customer", "[]"),
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.zero()));
        cs.add(new Case("A7", "ABAC", "Deny empty user: '' matches no subjectID.",
            "START: inject a claim with user='' (empty string). FILTER: 3b's subject match 'esa.subjectID = ctx.user' can never equal a real seeded subjectID. END: 0 rows — an empty identity matches nothing.",
            claim("", "100", "ABAC", "Customer", "[]"),
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.zero()));
        cs.add(new Case("A8", "ABAC", "Deny wrong root: root != the queried table's object type.",
            "START: inject the analyst claim but with root=Item while querying customer. FILTER: branch 3 gate 'ctx.root=object_type' is 'Item'='Customer' -> false, so branch 3 never runs; branch 2 needs 'Customer' in permissions (empty) -> false. END: 0 rows — root must name the object type of the table you are querying.",
            claim("u.analyst1@example.com", "100", "ABAC", "Item", "[]"),
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.zero()));
        cs.add(new Case("A9", "ABAC", "Non-root table via branch 2: StoreSale in permissions -> ALL store_sales rows (contrast A5's per-row assignment).",
            "START: inject the analyst claim with root=Customer but permissions=[Item,StoreSale], then query store_sales. FILTER: branch 2 fires because root('Customer')<>object_type('StoreSale') AND array_contains(permissions,'StoreSale') — coarse, assignment-independent access to the whole related table. END: ALL store_sales rows — contrast A5, where the same table was narrowed to one entity via 3b.",
            claim("u.analyst1@example.com", "100", "ABAC", "Customer", "[\"Item\",\"StoreSale\"]"),
            "SELECT count(*) FROM " + e.qualify("store_sales"), Expect.all()));

        // ---- B-perm. Non-root via the permissions (middle) branch — branch 2 ----
        cs.add(new Case("B1", "PERM", "Item visible via branch 2 (Item in permissions) -> ALL items.",
            "START: analyst claim, root=Customer, permissions=[Item,StoreSale], query item. FILTER: branch 2 'Customer<>Item AND array_contains(perms,'Item')' is TRUE for every item row. END: ALL items — a permissions entry grants table-wide read of that related object type.",
            claim("u.analyst1@example.com", "100", "ABAC", "Customer", "[\"Item\",\"StoreSale\"]"),
            "SELECT count(*) FROM " + e.qualify("item"), Expect.all()));
        cs.add(new Case("B2", "PERM", "store_sales via branch 2 (StoreSale in permissions) -> ALL sales.",
            "START: same claim as B1, query store_sales. FILTER: branch 2 fires via 'StoreSale' in permissions. END: ALL sales — one permissions claim opens every governed related table it lists (here both item and store_sales).",
            claim("u.analyst1@example.com", "100", "ABAC", "Customer", "[\"Item\",\"StoreSale\"]"),
            "SELECT count(*) FROM " + e.qualify("store_sales"), Expect.all()));
        // NOTE: use a GOVERNED table (item). `store`/`warehouse` have no policy in the current
        // deployment (only customer/item/store_sales are bound), so they return ALL rows regardless
        // of the claim — a deny test there would be meaningless. Here Item is omitted from permissions.
        cs.add(new Case("B3", "PERM", "Deny on a GOVERNED non-root table: item with Item NOT in permissions (only StoreSale) -> 0.",
            "START: analyst claim, root=Customer, permissions=[StoreSale] (Item deliberately omitted), query item. FILTER: branch 2 needs array_contains(perms,'Item') -> false; branch 3 gate root('Customer')=object_type('Item') -> false; no assignment path. END: 0 rows — permissions must list the exact object type of the queried table.",
            claim("u.analyst1@example.com", "100", "ABAC", "Customer", "[\"StoreSale\"]"),
            "SELECT count(*) FROM " + e.qualify("item"), Expect.zero()));
        cs.add(new Case("B4", "PERM", "Wrong format: 'items.view' != object type 'Item' -> branch 2 array_contains fails -> 0.",
            "START: analyst claim, permissions=['items.view','sales.view'] (dot-notation, not object types), query item. FILTER: branch 2 compares against the OBJECT TYPE string 'Item'; array_contains(['items.view',...],'Item') -> false. END: 0 rows — permissions hold object types (Item), not '.view' permission strings.",
            claim("u.analyst1@example.com", "100", "ABAC", "Customer", "[\"items.view\",\"sales.view\"]"),
            "SELECT count(*) FROM " + e.qualify("item"), Expect.zero()));

        // ---- R. RBAC_ABAC org tree — branch 3a, ADDITIVE with 3b (fixture populates SUITE_ORG) ----
        cs.add(new Case("R1", "RBAC", "RBAC_ABAC org=SUITE_ORG (additive 3a OR 3b): org-subtree children UNION analyst's own 2012 -> >0 rows.",
            "START: analyst claim, mode=RBAC_ABAC, org=SUITE_ORG (the fixture seeds 5 real customer addresses as live children of SUITE_ORG), query customer. FILTER: branch 3 gate opens; 3a 'mode=RBAC_ABAC AND c_current_addr_sk IN {children of SUITE_ORG}' matches customers at those addresses, UNION 3b EXISTS (the analyst's own 2012). END: >0 (observed 8) = the org set plus the one explicit grant — RBAC and ABAC are additive.",
            claim("u.analyst1@example.com", SUITE_ORG, "RBAC_ABAC", "Customer", "[]"),
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.nonzero()));
        cs.add(new Case("R2", "RBAC", "RBAC_ABAC is ADDITIVE (3a OR 3b): org=SUITE_EMPTY has no children (3a empty), but analyst's explicit assignment to 2012 (3b) still shows -> 1.",
            "START: analyst claim, mode=RBAC_ABAC, org=SUITE_EMPTY (no children seeded), query customer. FILTER: 3a's child set is empty so it contributes nothing, but 3b EXISTS still matches the analyst's assignment to 2012. END: 1 row — proves RBAC_ABAC ADDS to per-row grants rather than replacing them.",
            claim("u.analyst1@example.com", SUITE_EMPTY, "RBAC_ABAC", "Customer", "[]"),
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.exact(1)));
        cs.add(new Case("R3", "RBAC", "RBAC_ABAC does not help non-root tables (item): 3a lives only inside root=object_type -> 0.",
            "START: analyst claim, mode=RBAC_ABAC, root=Customer, query item. FILTER: item's object_type 'Item' <> root 'Customer', so branch 3 (where 3a lives) never opens; branch 2 needs 'Item' in permissions (empty). END: 0 rows — RBAC org access only ever applies to the root table.",
            claim("u.analyst1@example.com", SUITE_ORG, "RBAC_ABAC", "Customer", "[]"),
            "SELECT count(*) FROM " + e.qualify("item"), Expect.zero()));
        cs.add(new Case("R4", "RBAC", "RBAC_ABAC is org-driven: user with NO assignment -> only branch 3a's org subtree -> >0. Proves 3a is independent of 3b.",
            "START: a user with NO assignments anywhere (u.nobody), mode=RBAC_ABAC, org=SUITE_ORG, query customer. FILTER: 3b EXISTS finds nothing, but 3a matches customers at SUITE_ORG's live child addresses. END: >0 (observed 7) — 3a is purely org-driven, independent of any grant. (R1-R4 = 8-7 = 1 = the analyst's extra 2012, confirming additivity by arithmetic.)",
            claim("u.nobody@example.com", SUITE_ORG, "RBAC_ABAC", "Customer", "[]"),
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.nonzero()));

        // ---- T. tenant sensitivity — abac_row_filter NEVER references ctx.tenant, so it is INERT.
        //      Same claim, different tenant, must give the same rows. (Tenant isolation, if needed,
        //      must be enforced OUTSIDE this row filter — e.g. per-tenant catalogs/schemas or the app tier.)
        cs.add(new Case("T1", "TENANT", "tenant is not read by the filter: analyst+ABAC with tenant=999 (vs A2's tenant=1) -> identical result = 1.",
            "START: the A2 analyst claim but with tenant=999 instead of 1. FILTER: abac_row_filter never references ctx.tenant anywhere in its body, so the tenant value cannot affect any branch; evaluation is byte-identical to A2 (3b -> 2012). END: 1 row — tenant provides NO isolation in this filter; it must be enforced elsewhere (per-tenant catalogs/schemas or the app tier).",
            claim(999L, "u.analyst1@example.com", "100", "ABAC", "Customer", "[]"),
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.exact(1)));
        cs.add(new Case("T2", "TENANT", "tenant inert in RBAC_ABAC too: tenant=999, org=SUITE_ORG -> org still drives visibility -> same as R1 = >0.",
            "START: the R1 claim (mode=RBAC_ABAC, org=SUITE_ORG) but with tenant=999. FILTER: tenant is again unread; org still drives 3a and the analyst's grant drives 3b. END: >0 (observed 8, = R1) — tenant is inert even in RBAC_ABAC mode.",
            claim(999L, "u.analyst1@example.com", SUITE_ORG, "RBAC_ABAC", "Customer", "[]"),
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.nonzero()));

        // ---- O. org sensitivity — ctx.org is read ONLY by branch 3a, which needs mode=RBAC_ABAC.
        //      So org is INERT in plain ABAC, but DRIVES visibility (the child-org set) in RBAC_ABAC.
        cs.add(new Case("O1", "ORG", "org is inert in ABAC mode (3a is the only reader, and it needs RBAC_ABAC): org=ORG_UNUSED vs A2's org=100 -> EXISTS unchanged -> 1.",
            "START: the A2 analyst claim but org=ORG_UNUSED_999 and mode=ABAC. FILTER: ctx.org is read ONLY inside 3a, which requires mode=RBAC_ABAC; in ABAC mode 3a is false so org is never consulted, and 3b EXISTS (which ignores org) still matches 2012. END: 1 row — org is inert in plain ABAC.",
            claim(1L, "u.analyst1@example.com", "ORG_UNUSED_999", "ABAC", "Customer", "[]"),
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.exact(1)));
        cs.add(new Case("O2", "ORG", "org DRIVES RBAC_ABAC: user with NO assignment + org=SUITE_EMPTY (no children) -> 3a empty AND 3b empty -> 0. Mirror of R4 (org=SUITE_ORG -> >0); isolates the child-org set as 3a's sole input.",
            "START: u.nobody, mode=RBAC_ABAC, org=SUITE_EMPTY, query customer. FILTER: 3a's child set for SUITE_EMPTY is empty, and 3b finds no grant for nobody. END: 0 rows — in RBAC_ABAC org is the sole driver, so an empty org with no assignment yields nothing (the mirror image of R4's >0).",
            claim(1L, "u.nobody@example.com", SUITE_EMPTY, "RBAC_ABAC", "Customer", "[]"),
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.zero()));

        // ---- C. ctx / claim edge values (parsing & case-sensitivity) ----
        cs.add(new Case("C1", "EDGE", "mode 'abac' (lowercase): non-magic -> EXISTS path -> same as A2.",
            "START: analyst claim with mode='abac' (lowercase). FILTER: 'abac' is neither the magic 'DISABLE' nor 'RBAC_ABAC', so branches 1 and 3a are skipped and evaluation falls to 3b EXISTS exactly like A2. END: 1 row — any non-magic mode behaves as plain ABAC.",
            claim("u.analyst1@example.com", "100", "abac", "Customer", "[]"),
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.exact(1)));
        cs.add(new Case("C2", "EDGE", "mode 'disable' (lowercase): NOT allow-all (DISABLE is case-sensitive) -> 1.",
            "START: analyst claim with mode='disable' (lowercase). FILTER: branch 1 compares ctx.mode = 'DISABLE' case-SENSITIVELY, so 'disable' does NOT trigger allow-all; it falls through to 3b EXISTS (2012). END: 1 row (not ALL) — proving DISABLE is case-sensitive.",
            claim("u.analyst1@example.com", "100", "disable", "Customer", "[]"),
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.exact(1)));
        cs.add(new Case("C3", "EDGE", "root 'customer' (lowercase) != 'Customer' -> root branch fails -> 0.",
            "START: analyst claim with root='customer' (lowercase), query customer. FILTER: branch 3 gate 'ctx.root = object_type' is 'customer'='Customer' -> false (case-sensitive), and branch 2 array_contains fails too. END: 0 rows — root must match the object type's exact casing.",
            claim("u.analyst1@example.com", "100", "ABAC", "customer", "[]"),
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.zero()));
        cs.add(new Case("C4", "EDGE", "missing 'permissions': from_json null; root path unaffected -> 1.",
            "START: inject a claim JSON that omits the permissions field entirely. FILTER: from_json yields permissions=null, but the root/3b path never touches permissions, so entity 2012 still matches. END: 1 row — a missing array degrades safely on the root path.",
            "{\"tenant\":1,\"user\":\"u.analyst1@example.com\",\"org\":\"100\",\"mode\":\"ABAC\",\"root\":\"Customer\"}",
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.exact(1)));
        cs.add(new Case("C5", "EDGE", "extra unknown field 'scope' ignored by from_json -> 1.",
            "START: inject a claim with an extra unknown key 'scope':'xyz'. FILTER: from_json drops fields not in the target STRUCT, so ctx is parsed exactly as A2. END: 1 row — unknown claim fields are harmlessly ignored.",
            "{\"tenant\":1,\"user\":\"u.analyst1@example.com\",\"org\":\"100\",\"mode\":\"ABAC\",\"root\":\"Customer\",\"permissions\":[],\"scope\":\"xyz\"}",
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.exact(1)));
        cs.add(new Case("C6", "EDGE", "tenant as string \"1\": from_json tolerates the type mismatch; row set unchanged -> 1 (asserted; observed run: 1).",
            "START: inject a claim where tenant is the STRING '1' instead of the int 1 (a type mismatch for the STRUCT's tenant:int). FILTER: from_json tolerates the mismatch (it does NOT null the whole struct — proven by the observed run), and since tenant is unused by the filter the row set is identical to A2. END: exactly 1 row — now ASSERTED (observed run returned 1), confirming a string-typed tenant still parses and does not affect the decision.",
            "{\"tenant\":\"1\",\"user\":\"u.analyst1@example.com\",\"org\":\"100\",\"mode\":\"ABAC\",\"root\":\"Customer\",\"permissions\":[]}",
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.exact(1)));
        cs.add(new Case("C7", "EDGE", "empty claim {}: all fields null -> secure default deny -> 0.",
            "START: inject the literal empty claim '{}'. FILTER: every ctx field parses to null -> mode null (no DISABLE), root null (gate 'null=object_type' false), user null (3b matches nothing). END: 0 rows — a malformed/empty claim fails closed (secure default deny).",
            "{}",
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.zero()));
        cs.add(new Case("C8", "EDGE", "user mixed-case: exact subjectID compare fails -> 0.",
            "START: inject a claim with user='U.Analyst1@example.com' (mixed case) vs the seeded 'u.analyst1@example.com'. FILTER: 3b's subject match 'esa.subjectID = ctx.user' is a case-sensitive string compare and fails. END: 0 rows — identities are matched exactly, case included.",
            claim("U.Analyst1@example.com", "100", "ABAC", "Customer", "[]"),
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.zero()));

        // ---- W. Two conflicting row-filter policies on `warehouse` (allow-all + deny-all) ----
        // EMPIRICAL FINDING: Unity Catalog does NOT combine them (not AND, not OR). Both CREATE POLICY
        // succeed, but at QUERY time UC rejects the query — at most one row filter per table:
        //   [UC_ABAC_MULTIPLE_ROW_FILTERS] ... resulted in multiple row filters. At most one is allowed.
        // The conflict functions are constant, so the outcome is a query ERROR regardless of the claim.
        cs.add(new Case("W1", "CONFLICT",
            "Two policies on warehouse (allow-all + deny-all): UC rejects the query at eval time — at most one row filter per table.",
            "START: warehouse carries TWO row-filter policies (an allow-all and a deny-all) created earlier in the UI; the claim is irrelevant because the conflict functions are constant. FILTER: at query planning UC finds the table resolves to more than one row filter and refuses to combine them (not AND, not OR). END: the query is REJECTED at eval time with UC_ABAC_MULTIPLE_ROW_FILTERS — at most one row filter per table (surfaced as SQLSTATE 42KDJ).",
            DISABLE_CLAIM,   // claim is irrelevant here — the conflict functions are constant
            "SELECT count(*) FROM " + e.qualify(CONFLICT_TABLE),
            Expect.errorContains("UC_ABAC_MULTIPLE_ROW_FILTERS")));

        // ---- N. Newly-onboarded governed tables (sql/13): one assignment CONDITION each.
        //      Each queried as u.analyst1@example.com with root = that table's object type, mode=ABAC
        //      (so branch 3b EXISTS decides it). The flag under test flips the row on/off.
        cs.add(new Case("N1", "META",
            "promotion: its esa row has isDeleted=true -> 'WHERE esa.isDeleted=false' drops it -> 0 (negative).",
            "START: promotion was onboarded (sql/13) under abac_row_filter_wrapper with its p_promo_sk assigned to the analyst, but that ABAC_EntitySubjectAssignment row has isDeleted=true; inject the analyst claim with root=Promotion. FILTER: branch 3 gate opens (object type 'Promotion' passed as the policy literal), and 3b's EXISTS is filtered by 'WHERE esa.isDeleted=false', which drops the only matching grant. END: 0 rows — a soft-deleted esa row grants nothing (negative).",
            claim("u.analyst1@example.com", "100", "ABAC", "Promotion", "[]"),
            "SELECT count(*) FROM " + e.qualify("promotion"), Expect.zero()));
        cs.add(new Case("N2", "META",
            "store: esa subjectType=USER_GROUP (test_group_1); analyst1 is a member (UserGroupMembers) -> group path grants the one assigned store -> 1 (positive; proves group access + new-table onboarding).",
            "START: store was onboarded (sql/13); its assignment targets subjectType=USER_GROUP subjectID='test_group_1', and UserGroupMembers holds (test_group_1, u.analyst1@example.com, isDeleted=false); inject the analyst claim with root=Store. FILTER: 3b's LEFT JOIN to UserGroupMembers matches because the analyst is a member of the granted group, and ABAC_Assignment assignment_store_1 is active and not deleted, so EXISTS is TRUE for the one assigned s_store_sk. END: 1 row — proves the group-membership grant path AND that a brand-new table onboards correctly.",
            claim("u.analyst1@example.com", "100", "ABAC", "Store", "[]"),
            "SELECT count(*) FROM " + e.qualify("store"), Expect.exact(1)));
        cs.add(new Case("N3", "META",
            "call_center: its ABAC_Assignment has isActive=false -> the 'JOIN ... AND a.isActive' fails -> 0 (negative).",
            "START: call_center was onboarded (sql/13) with a normal USER_ID esa row for the analyst, but its parent ABAC_Assignment (assignment_cc_1) has isActive=false; inject root=CallCenter. FILTER: 3b's 'JOIN ABAC_Assignment a ON ... AND a.isActive' drops the grant because the assignment is switched off. END: 0 rows — an inactive assignment revokes all of its esa grants (negative).",
            claim("u.analyst1@example.com", "100", "ABAC", "CallCenter", "[]"),
            "SELECT count(*) FROM " + e.qualify("call_center"), Expect.zero()));
        cs.add(new Case("N4", "META",
            "ship_mode: its ABAC_Assignment has isDeleted=true -> the 'AND a.isDeleted=false' fails -> 0 (negative).",
            "START: ship_mode was onboarded (sql/13) with a normal esa row, but its ABAC_Assignment (assignment_ship_1) has isDeleted=true; inject root=ShipMode. FILTER: 3b's 'AND a.isDeleted=false' on the assignment join fails. END: 0 rows — a soft-deleted assignment record also kills its grants (negative).",
            claim("u.analyst1@example.com", "100", "ABAC", "ShipMode", "[]"),
            "SELECT count(*) FROM " + e.qualify("ship_mode"), Expect.zero()));

        // ---- ORG-DEL. Soft-deleted orgHierarchy child (sql/13). The SAME customer address is a LIVE
        //      child of LIVE_ORG and a DELETED child of DEL_ORG, so the pair isolates the isDeleted flag.
        cs.add(new Case("ODEL", "RBAC",
            "DEL_ORG's only child is soft-deleted (isDeleted=true) -> excluded from branch 3a's child set; nobody has no assignment (3b) -> 0 (negative).",
            "START: sql/13 seeded one real customer address as a child of DEL_ORG with isDeleted=true; inject u.nobody, mode=RBAC_ABAC, org=DEL_ORG. FILTER: 3a's child subquery filters 'WHERE parentOrgID=ctx.org AND isDeleted=false', so DEL_ORG's only child is excluded and the set is empty; 3b finds no grant for nobody. END: 0 rows — a soft-deleted orgHierarchy edge is excluded from RBAC visibility (negative).",
            claim("u.nobody@example.com", "DEL_ORG", "RBAC_ABAC", "Customer", "[]"),
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.zero()));
        cs.add(new Case("OLIVE", "RBAC",
            "Control: the SAME address is a LIVE child of LIVE_ORG (isDeleted=false) -> branch 3a includes it -> >0. Proves the isDeleted flag (not emptiness) is what excludes ODEL.",
            "START: the SAME customer address is also seeded as a LIVE child of LIVE_ORG (isDeleted=false); inject u.nobody, mode=RBAC_ABAC, org=LIVE_ORG. FILTER: 3a's child set now contains that address, so customers there pass. END: >0 (observed 1) — because it is the same address as ODEL, the only difference is the isDeleted flag, proving the flag (not emptiness) is what excludes ODEL.",
            claim("u.nobody@example.com", "LIVE_ORG", "RBAC_ABAC", "Customer", "[]"),
            "SELECT count(*) FROM " + e.qualify("customer"), Expect.nonzero()));

        // ---- W2-W4. Two ROW-FILTER conflict tables (sql/12) — NEGATIVE: every query ERRORS.
        //      web_page = DIFFERENT bindings (rf_page_1 on col1,col2 ; rf_page_2 on col2).
        //      web_site = SAME single column (rf_site_1 and rf_site_2 both on web_site_sk).
        //      Row filters are TABLE-WIDE (max 1/table), so the column list is irrelevant to the conflict.
        cs.add(new Case("WP1", "CONFLICT",
            "web_page count(*): two row filters with DIFFERENT bindings (rf1 col1,col2 ; rf2 col2) -> at most one row filter per table.",
            "START: web_page (sql/12) carries two row filters with DIFFERENT bindings — rf_page_1 on (wp_web_page_sk, wp_access_date_sk) and rf_page_2 on (wp_access_date_sk); the claim is irrelevant. FILTER: UC sees the table resolve to two row filters and refuses to combine them, regardless of their column lists. END: query REJECTED with UC_ABAC_MULTIPLE_ROW_FILTERS — different column bindings do NOT let two row filters coexist.",
            DISABLE_CLAIM,
            "SELECT count(*) FROM " + e.qualify("web_page"),
            Expect.errorContains("UC_ABAC_MULTIPLE_ROW_FILTERS")));
        cs.add(new Case("WP2", "CONFLICT",
            "web_page SELECT wp_web_page_sk: this column is bound by rf1 ONLY, yet still errors -> the conflict is table-wide, not tied to the shared col2.",
            "START: the same two web_page policies as WP1, but the query projects only wp_web_page_sk, which is bound by rf_page_1 ONLY. FILTER: the conflict is detected at the TABLE level during planning, before any column-specific evaluation. END: still REJECTED with UC_ABAC_MULTIPLE_ROW_FILTERS — the row-filter conflict is table-wide, not scoped to the shared column.",
            DISABLE_CLAIM,
            "SELECT wp_web_page_sk FROM " + e.qualify("web_page"),
            Expect.errorContains("UC_ABAC_MULTIPLE_ROW_FILTERS")));
        cs.add(new Case("WS1", "CONFLICT",
            "web_site count(*): two row filters on the SAME column (web_site_sk) -> at most one row filter per table.",
            "START: web_site (sql/12) carries two row filters (rf_site_1, rf_site_2) both bound to the SAME column web_site_sk; the claim is irrelevant. FILTER: two row filters on one table is the disallowed condition irrespective of whether they share a column. END: query REJECTED with UC_ABAC_MULTIPLE_ROW_FILTERS — 'multiple filters on the same column' is just another instance of the one-row-filter-per-table rule.",
            DISABLE_CLAIM,
            "SELECT count(*) FROM " + e.qualify("web_site"),
            Expect.errorContains("UC_ABAC_MULTIPLE_ROW_FILTERS")));

        // ---- TH. Threshold / range grant on `inventory` (sql/14): a SEPARATE filter whose 3b uses
        //      '>=' not '='. The analyst is assigned value 500 on object type 'Inventory', thresholding
        //      inv_quantity_on_hand. Contrast the deployed exact-match filter, where an assignment of
        //      500 would show ONLY rows whose quantity is EXACTLY 500.
        cs.add(new Case("TH1", "THRESH",
            "Range grant: analyst assigned 500 -> inventory rows with inv_quantity_on_hand >= 500 are visible -> >0 rows.",
            "START: sql/14 built a separate abac_row_filter_threshold (3b matches 'try_cast(entity_id AS BIGINT) >= try_cast(esa.entityID AS BIGINT)') and bound it to inventory with inv_quantity_on_hand tagged as the id; the analyst has an assignment entityID=500 on object type 'Inventory'; inject the analyst claim with root=Inventory. FILTER: each inventory row passes 3b when its inv_quantity_on_hand >= 500. END: >0 rows — the at/above-threshold slice of a large fact table.",
            claim("u.analyst1@example.com", "100", "ABAC", "Inventory", "[]"),
            "SELECT count(*) FROM " + e.qualify(THRESH_TABLE), Expect.nonzero()));
        cs.add(new Case("TH2", "THRESH",
            "The cutoff holds: among VISIBLE rows, none are below the threshold -> count where inv_quantity_on_hand < 500 is exactly 0 (deterministic, data-independent).",
            "START: same threshold filter + analyst claim (root=Inventory, assigned 500) as TH1. FILTER: the row filter is ANDed with the query, so effectively 'inv_quantity_on_hand >= 500 AND inv_quantity_on_hand < 500' -> impossible for every row. END: 0 rows — proves nothing below the threshold leaks, regardless of the data distribution (the killer assertion for the range semantic).",
            claim("u.analyst1@example.com", "100", "ABAC", "Inventory", "[]"),
            "SELECT count(*) FROM " + e.qualify(THRESH_TABLE) + " WHERE inv_quantity_on_hand < 500", Expect.zero()));
        cs.add(new Case("TH3", "THRESH",
            "The floor holds: the minimum visible inv_quantity_on_hand is >= 500 (asserted; observed run: 500).",
            "START/FILTER: same threshold filter and claim as TH1, but aggregate the minimum quantity among the rows the filter lets through. END: min(inv_quantity_on_hand) is ASSERTED to be >= 500 (observed run returned exactly 500 — the boundary), confirming the floor the '>=' predicate enforces: no row below the threshold is ever visible. (>= rather than = 500 so a future data shift that keeps the floor doesn't false-fail.)",
            claim("u.analyst1@example.com", "100", "ABAC", "Inventory", "[]"),
            "SELECT min(inv_quantity_on_hand) FROM " + e.qualify(THRESH_TABLE), Expect.atLeast(500)));

        // ---- DR1. Direct CLASSIC Row-Level Security on `reason` (sql/15): NO governance tags, NO
        //      CREATE POLICY — the row filter is bound straight to the column via ALTER TABLE ... SET
        //      ROW FILTER. Contrast every other case (ABAC has_tag() policies) and DR2 below.
        cs.add(new Case("DR1", "RLS",
            "Direct classic RLS (NO tags, NO policy): reason has SET ROW FILTER rls_reason ON (r_reason_sk) keeping r_reason_sk >= 20 -> count where < 20 is 0.",
            "START: sql/15 attached a CLASSIC Unity Catalog row filter DIRECTLY to reason via 'ALTER TABLE reason SET ROW FILTER rls_reason ON (r_reason_sk)' — no governed tag, no CREATE POLICY, no ABAC wrapper; the filter fn is the pure predicate 'k >= 20' and ignores any claim. FILTER: UC applies the table-bound row filter to the SP, keeping only r_reason_sk >= 20. END: count(*) WHERE r_reason_sk < 20 = 0 — a data-independent proof that the classic (table-managed) RLS form filters WITHOUT any ABAC tag/policy machinery. Contrast DR2, which does the same via a has_tag() policy.",
            DISABLE_CLAIM,
            "SELECT count(*) FROM " + e.qualify("reason") + " WHERE r_reason_sk < 20", Expect.zero()));

        // ---- V. Do row filters propagate through VIEWS? (sql/16) `reason` carries classic RLS
        //      (rls_reason, r_reason_sk >= 20) and `income_band` carries the ABAC has_tag() policy
        //      (income_band_dr2_policy, cutoff <= 10 of 20 fixed rows), both bound in sql/15. sql/16
        //      adds two views over those already-governed base tables and grants the SP SELECT on
        //      the views — proving the base table's row filter follows through the view rather than
        //      being bypassed. (v_income_band_governed is named for what it is: income_band IS
        //      governed, and that governance holding through the view is exactly what V2 tests.)
        cs.add(new Case("V1", "V",
            "View over a governed base table inherits the base row filter",
            "reason carries classic RLS (rls_reason: r_reason_sk >= 20) from sql/15. Querying THROUGH "
          + "v_reason_governed must still exclude r_reason_sk < 20 — a view must not be a bypass.",
            DISABLE_CLAIM,
            "SELECT count(*) FROM " + e.qualify("v_reason_governed") + " WHERE r_reason_sk < 20",
            Expect.zero(),
            Set.of(Capability.CLASSIC_RLS, Capability.VIEWS)));

        cs.add(new Case("V2", "V",
            "View over a table governed by an ABAC policy still filters",
            "v_income_band_governed selects from income_band, which carries the DR2 ABAC has_tag() "
          + "policy (income_band_dr2_policy, cutoff ib_income_band_sk <= 10) from sql/15. Through the "
          + "view the same 10-of-20 restriction must hold.",
            DISABLE_CLAIM,
            "SELECT count(*) FROM " + e.qualify("v_income_band_governed"),
            Expect.exact(10),
            Set.of(Capability.POLICY_DDL, Capability.TAGS, Capability.VIEWS)));

        cs.add(new Case("V3", "V",
            "Aggregate through a view cannot leak filtered rows",
            "max(r_reason_sk) is unremarkable, but min() through the view must be >= 20: an aggregate "
          + "computed over filtered-out rows would reveal their existence.",
            DISABLE_CLAIM,
            "SELECT min(r_reason_sk) FROM " + e.qualify("v_reason_governed"),
            Expect.atLeast(20),
            Set.of(Capability.CLASSIC_RLS, Capability.VIEWS)));

        // ---- SC. Policy SCOPE (sql/17): an isolated schema (abac_tpcds.abac_scope) so a
        //      SCHEMA-level policy cannot reach any main-suite table. These tables live in a
        //      DIFFERENT schema than the rest of the suite, so they are referenced by literal
        //      qualified name below, NOT via e.qualify() (which prefixes abac_tpcds.tpcds_1_delta).
        //      scope_schema_policy (ON SCHEMA) binds has_tag('abac_scope_id') -> scope_filter
        //      (id <= 10 of 20 fixed rows) across every tagged table in the schema:
        //        SC1 = scoped_a      -- the first table it governs.
        //        SC2 = scoped_c      -- a THIRD, otherwise-unrelated table with the SAME tag and
        //                               governed by NOTHING else, proving schema scope covers every
        //                               matching member, not just the first (scoped_a).
        //        SC3 = ungoverned    -- inside the ON SCHEMA scope but carries NO matching tag, so
        //                               MATCH COLUMNS finds nothing and the policy silently does not
        //                               apply. THE DANGER: a broken policy fails CLOSED; a
        //                               non-matching one fails OPEN -- unfiltered, with no error.
        //        SC4 = scoped_b      -- ALSO carries a second, TABLE-level policy (scope_table_policy,
        //                               id <= 5). NOT a precedence contest: Unity Catalog allows at
        //                               most one row filter per table, enforced at query time,
        //                               table-wide. Both CREATE POLICY statements succeed; the QUERY
        //                               fails with UC_ABAC_MULTIPLE_ROW_FILTERS (SQLSTATE 42KDJ) --
        //                               NOT "the more specific (table) policy wins". scoped_b exists
        //                               solely to carry this conflict; scoped_c (SC2) is unaffected,
        //                               so the whole script applies in one pass and stays re-runnable.
        final String SCOPE = "abac_tpcds.abac_scope.";

        cs.add(new Case("SC1", "SC",
            "ON SCHEMA policy governs a table in that schema",
            "scope_schema_policy is bound ON SCHEMA, not ON TABLE. scoped_a has the tagged id column, "
          + "so scope_filter (id <= 10) applies: 10 of 20 rows.",
            DISABLE_CLAIM,
            "SELECT count(*) FROM " + SCOPE + "scoped_a",
            Expect.exact(10),
            Set.of(Capability.POLICY_DDL, Capability.TAGS, Capability.SCHEMA_SCOPE)));

        cs.add(new Case("SC2", "SC",
            "ON SCHEMA policy covers EVERY matching member, not just the first",
            "scoped_c is a THIRD table in the same schema, tagged the same way, and governed by "
          + "NOTHING but the schema-level policy (unlike scoped_b, which also carries SC4's "
          + "table-level policy and exists solely for that conflict). Schema scope is a search "
          + "scope, so it must govern scoped_c identically to scoped_a -- proving scope is not "
          + "limited to the first table it happened to bind. Deterministic count: nothing above "
          + "the id <= 10 cutoff is visible.",
            DISABLE_CLAIM,
            "SELECT count(*) FROM " + SCOPE + "scoped_c WHERE id > 10",
            Expect.zero(),
            Set.of(Capability.POLICY_DDL, Capability.TAGS, Capability.SCHEMA_SCOPE)));

        cs.add(new Case("SC3", "SC",
            "A table with no matching tag is NOT governed -- returns ALL rows",
            "`ungoverned` sits inside the policy's ON SCHEMA scope but has no abac_scope_id tag on "
          + "any column, so MATCH COLUMNS finds nothing for this table and the policy silently does "
          + "not apply. This is the documented dangerous case: a BROKEN policy fails CLOSED (errors, "
          + "or blocks everything); a NON-MATCHING one fails OPEN -- unfiltered access, no error at all.",
            DISABLE_CLAIM,
            "SELECT count(*) FROM " + SCOPE + "ungoverned",
            Expect.exact(20),
            Set.of(Capability.POLICY_DDL, Capability.TAGS, Capability.SCHEMA_SCOPE)));

        cs.add(new Case("SC4", "SC",
            "Schema-level + table-level row filters CONFLICT -- they do not have a precedence order",
            "scoped_b is covered by BOTH scope_schema_policy (ON SCHEMA) and scope_table_policy "
          + "(ON TABLE). This is NOT a precedence test: Databricks permits at most ONE row filter "
          + "per table, enforced at query time, table-wide. Both CREATE POLICY statements succeed; "
          + "the QUERY fails with UC_ABAC_MULTIPLE_ROW_FILTERS (SQLSTATE 42KDJ) -- NOT 'the more "
          + "specific (table) policy wins'. A planner that silently picked one would be wrong, and "
          + "that is exactly what this case disproves.",
            DISABLE_CLAIM,
            "SELECT count(*) FROM " + SCOPE + "scoped_b",
            Expect.errorContains("UC_ABAC_MULTIPLE_ROW_FILTERS"),
            Set.of(Capability.POLICY_DDL, Capability.TAGS, Capability.SCHEMA_SCOPE)));

        return cs;
    }
}
