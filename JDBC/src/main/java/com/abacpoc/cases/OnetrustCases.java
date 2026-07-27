package com.abacpoc.cases;

import com.abacpoc.engine.Capability;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * OneTrust deployment (abac_onetrust.onetrust_sim) cases: OT-T1..OT-T8, the OAuth equivalents of
 * sql_onetrust/06_test_cases.sql's owner-side T1-T8, run as the SP under a real custom claim
 * through the deployed policy -- see sql_onetrust/07_oauth_wiring.sql (abac_row_filter_wrapper_oauth).
 * Plus all 50 real compatible queries from onetrust/onetrust_sanity_run_annotated.csv, run as
 * INFO cases (checked for execution success only -- these are real customer query shapes with no
 * known-correct row count on synthetic data, unlike T1-T8 which assert against the real seed).
 *
 * Independent of Cases.java / Engine.qualify(): this deployment lives in a different
 * catalog/schema than the TPC-DS suite (abac_onetrust.onetrust_sim vs abac_tpcds.tpcds_1_delta),
 * so tables are qualified directly here. Like the TPC-DS A-series cases, OT-T1..OT-T4/T7/T8 rely
 * on the ATTACHED POLICY filtering transparently (plain SELECT, no explicit UDF call) -- that is
 * the realistic test of "does the deployed policy work", not a re-implementation of the check.
 * Requires sql_onetrust/01-07 already applied against a real workspace.
 */
public final class OnetrustCases {

    private static final String SCHEMA = "abac_onetrust.onetrust_sim";

    // The single real orgID all 14 verbatim cmb_v_inventoryaggregatedrisksummary rows carry --
    // loaded verbatim from onetrust/onetrust_sample_data (deterministic, same in every
    // environment running this dataset, not something that needs a runtime probe).
    private static final String RBAC_ORG_ID = "b99df4a4-2bf5-4c08-9483-bd636470bc11";

    private static final Set<Capability> NEEDS_CLAIM_SWAP = Set.of(Capability.CLAIM_SWAP);

    private OnetrustCases() { }

    private static String q(String table) { return SCHEMA + "." + table; }

    public static List<Case> all() {
        List<Case> cs = new ArrayList<>();
        cs.addAll(functionalCases());
        cs.addAll(abacGroupCases());
        cs.addAll(permGroupCases());
        cs.addAll(rbacGroupCases());
        cs.addAll(tenantOrgGroupCases());
        cs.addAll(edgeGroupCases());
        cs.addAll(conflictGroupCases());
        cs.addAll(metaGroupCases());
        cs.addAll(threshGroupCases());
        cs.addAll(rlsGroupCases());
        cs.addAll(viewGroupCases());
        cs.addAll(scGroupCases());
        cs.addAll(tgGroupCases());
        cs.addAll(ucGroupCases());
        cs.addAll(xtGroupCases());
        cs.addAll(exGroupCases());
        cs.addAll(clGroupCases());
        cs.addAll(compatibleQueryCases());
        return cs;
    }

    public static List<Case> functionalCases() {
        String ownerClaim = Cases.claim("u.assessment.owner@example.com", "100", "ABAC", "ASSESSMENT", "[\"TEMPLATE\"]");
        String groupMemberClaim = Cases.claim("u.group.member@example.com", "100", "ABAC", "CONTROL", "[]");
        String inactiveClaim = Cases.claim("u.inactive.grant@example.com", "100", "ABAC", "ASSESSMENT", "[]");
        String disabledClaim = Cases.claim("u.disabled.mode@example.com", "100", "DISABLE", "ASSESSMENT", "[]");
        String rbacClaim = Cases.claim("u.rbac.viewer@example.com", RBAC_ORG_ID, "RBAC_ABAC", "ASSETS", "[]");

        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-T1", "ONETRUST", "root type, explicit assignment -> seeded assessment IS visible",
            "OAuth equivalent of 06_test_cases.sql T1. The policy attached to cmb_assessment filters "
                + "transparently under this claim; the WHERE clause narrows to the one entity seeded for this user.",
            ownerClaim,
            "SELECT count(*) FROM " + q("cmb_assessment")
                + " WHERE id = (SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
                + " WHERE subjectId = 'u.assessment.owner@example.com' AND objectType = 'ASSESSMENT' LIMIT 1)",
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-T2", "ONETRUST", "root type, no assignment -> a DIFFERENT assessment is NOT visible",
            "OAuth equivalent of 06_test_cases.sql T2.",
            ownerClaim,
            "SELECT count(*) FROM " + q("cmb_assessment")
                + " WHERE id != (SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
                + " WHERE subjectId = 'u.assessment.owner@example.com' AND objectType = 'ASSESSMENT' LIMIT 1)"
                + " AND id NOT IN (SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
                + " WHERE subjectId = 'u.assessment.owner@example.com')",
            Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-T3", "ONETRUST", "non-root type, IN permissions array -> ALL cmb_template rows visible",
            "OAuth equivalent of 06_test_cases.sql T3. No WHERE clause at all -- Expect.all() re-runs "
                + "the same SQL under DISABLE and asserts the counts match, same idiom as the TPC-DS A1-style cases.",
            ownerClaim,
            "SELECT count(*) FROM " + q("cmb_template"),
            Expect.all(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-T4", "ONETRUST", "non-root type, NOT in permissions array -> ZERO controls visible",
            "OAuth equivalent of 06_test_cases.sql T4.",
            ownerClaim,
            "SELECT count(*) FROM " + q("cmb_controlimplementation"),
            Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-T5", "ONETRUST", "group membership -> a member of test_group_1 sees the group-assigned control",
            "OAuth equivalent of 06_test_cases.sql T5.",
            groupMemberClaim,
            "SELECT count(*) FROM " + q("cmb_controlimplementation")
                + " WHERE id = (SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
                + " WHERE subjectId = 'test_group_1' AND objectType = 'CONTROL' LIMIT 1)",
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-T6", "ONETRUST", "isActive=false assignment -> must NOT grant visibility",
            "OAuth equivalent of 06_test_cases.sql T6.",
            inactiveClaim,
            "SELECT count(*) FROM " + q("cmb_assessment")
                + " WHERE id = (SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
                + " WHERE subjectId = 'u.inactive.grant@example.com' LIMIT 1)",
            Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-T7", "ONETRUST", "DISABLE mode -> everything visible regardless of assignments",
            "OAuth equivalent of 06_test_cases.sql T7.",
            disabledClaim,
            "SELECT count(*) FROM " + q("cmb_assessment"),
            Expect.all(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-T8", "ONETRUST", "RBAC_ABAC over the real orgHierarchy ancestor closure",
            "OAuth equivalent of 06_test_cases.sql T8. org is the one real orgID all 14 verbatim rows "
                + "of this table carry -- makes the RBAC_ABAC org-subtree branch fire against real profiled data.",
            rbacClaim,
            "SELECT count(*) FROM " + q("cmb_v_inventoryaggregatedrisksummary") + " WHERE upper(inventoryType) = 'ASSETS'",
            Expect.atLeast(1), NEEDS_CLAIM_SWAP));

        return cs;
    }

    /**
     * Mirrors TPC-DS's A1-A9 (Cases.java) -- root-type explicit assignment: baseline allow,
     * exact-id-list, same mechanism on a 2nd/3rd table, deny variants, branch-2 permissions.
     * OT-A5 is an honest adaptation, not a 1:1 port -- see the class-level note below.
     */
    public static List<Case> abacGroupCases() {
        String ownerClaim = Cases.claim("u.assessment.owner@example.com", "100", "ABAC", "ASSESSMENT", "[]");
        String disabledClaim = Cases.claim("u.disabled.mode@example.com", "100", "DISABLE", "ASSESSMENT", "[]");
        String templateOwnerClaim = Cases.claim("u.template.owner@example.com", "100", "ABAC", "TEMPLATE", "[]");
        String groupMemberClaim = Cases.claim("u.group.member@example.com", "100", "ABAC", "CONTROL", "[]");
        String emptyUserClaim = Cases.claim("", "100", "ABAC", "ASSESSMENT", "[]");
        String permissionsClaim = Cases.claim("u.assessment.owner@example.com", "100", "ABAC", "ASSESSMENT", "[\"CONTROL\"]");

        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-A1", "ABAC", "DISABLE -> branch 1 fires; show all cmb_assessment rows, identity ignored.",
            "Mirrors TPC-DS A1.",
            disabledClaim, "SELECT count(*) FROM " + q("cmb_assessment"), Expect.all(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-A2", "ABAC", "Baseline: branch 3b EXISTS matches the seeded assessment -> 1.",
            "Mirrors TPC-DS A2 (the baseline everything else in this group contrasts against).",
            ownerClaim,
            "SELECT count(*) FROM " + q("cmb_assessment")
                + " WHERE id = (SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
                + " WHERE subjectId = 'u.assessment.owner@example.com' AND objectType = 'ASSESSMENT' LIMIT 1)",
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-A3", "ABAC", "The visible id list is exactly [cmb_assessment_0] (asserted; observed run: cmb_assessment_0).",
            "Mirrors TPC-DS A3. Same claim/evaluation as OT-A2, but projects id instead of count(*) -- "
                + "proves the analyst sees precisely their assigned entity and no other id leaks.",
            ownerClaim, "SELECT id FROM " + q("cmb_assessment") + " ORDER BY id",
            Expect.exactIds("cmb_assessment_0"), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-A4", "ABAC", "Template tester -> same mechanism, different table (cmb_template).",
            "Mirrors TPC-DS A4. u.template.owner's real explicit assignment (seeded Task 2) on the same "
                + "direct-USER_ID mechanism as OT-A2, on a different table.",
            templateOwnerClaim,
            "SELECT count(*) FROM " + q("cmb_template")
                + " WHERE id = (SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
                + " WHERE subjectId = 'u.template.owner@example.com' AND objectType = 'TEMPLATE' LIMIT 1)",
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-A5", "ABAC",
            "Group tester -> same table-count mechanism via GROUP membership, not direct USER_ID (adapted from TPC-DS A5).",
            "TPC-DS A5 demonstrates one assignment granting MANY physical rows sharing an entity id "
                + "(store_sales fan-out) -- no currently-policied OneTrust table has that shape (each is "
                + "~1 real row per real id). Adapted to keep this group's mechanism coverage non-redundant: "
                + "OT-A5 is the GROUP-membership grant (distinct from OT-A2/OT-A4's direct USER_ID grants).",
            groupMemberClaim,
            "SELECT count(*) FROM " + q("cmb_controlimplementation")
                + " WHERE id = (SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
                + " WHERE subjectId = 'test_group_1' AND objectType = 'CONTROL' LIMIT 1)",
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-A6", "ABAC", "Deny wrong user: template tester has no ASSESSMENT assignment.",
            "Mirrors TPC-DS A6. u.template.owner is assigned only on cmb_template (TEMPLATE); querying "
                + "cmb_assessment under root=ASSESSMENT finds no matching grant.",
            Cases.claim("u.template.owner@example.com", "100", "ABAC", "ASSESSMENT", "[]"), "SELECT count(*) FROM " + q("cmb_assessment"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-A7", "ABAC", "Deny empty user: '' matches no real subjectID.",
            "Mirrors TPC-DS A7.",
            emptyUserClaim, "SELECT count(*) FROM " + q("cmb_assessment"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-A8", "ABAC", "Deny wrong root: root != the queried table's object type.",
            "Mirrors TPC-DS A8. u.assessment.owner's claim but root=CONTROL while querying cmb_assessment.",
            Cases.claim("u.assessment.owner@example.com", "100", "ABAC", "CONTROL", "[]"),
            "SELECT count(*) FROM " + q("cmb_assessment"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-A9", "ABAC", "Non-root table via branch 2: CONTROL in permissions -> ALL cmb_controlimplementation rows.",
            "Mirrors TPC-DS A9. root=ASSESSMENT but permissions=[CONTROL]; branch 2 fires because "
                + "root<>object_type AND array_contains(permissions,'CONTROL') -- coarse, "
                + "assignment-independent access to the whole related table (contrast OT-A5's per-row grant).",
            permissionsClaim, "SELECT count(*) FROM " + q("cmb_controlimplementation"), Expect.all(), NEEDS_CLAIM_SWAP));

        return cs;
    }

    /** Mirrors TPC-DS's B1-B4 (Cases.java) -- the permissions (branch 2) path in isolation. */
    public static List<Case> permGroupCases() {
        String multiPermClaim = Cases.claim("u.assessment.owner@example.com", "100", "ABAC", "ASSESSMENT", "[\"CONTROL\",\"TEMPLATE\"]");
        String omittedPermClaim = Cases.claim("u.assessment.owner@example.com", "100", "ABAC", "ASSESSMENT", "[\"TEMPLATE\"]");
        String wrongFormatClaim = Cases.claim("u.assessment.owner@example.com", "100", "ABAC", "ASSESSMENT", "[\"control.view\",\"template.view\"]");

        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-B1", "PERM", "CONTROL visible via branch 2 (CONTROL in permissions) -> ALL controls.",
            "Mirrors TPC-DS B1. root=ASSESSMENT, permissions=[CONTROL,TEMPLATE], query cmb_controlimplementation.",
            multiPermClaim, "SELECT count(*) FROM " + q("cmb_controlimplementation"), Expect.all(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-B2", "PERM", "cmb_template via branch 2 (TEMPLATE in permissions) -> ALL templates.",
            "Mirrors TPC-DS B2. Same claim as OT-B1, query cmb_template -- one permissions claim opens "
                + "every governed related table it lists.",
            multiPermClaim, "SELECT count(*) FROM " + q("cmb_template"), Expect.all(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-B3", "PERM", "Deny on a GOVERNED non-root table: CONTROL NOT in permissions (only TEMPLATE) -> 0.",
            "Mirrors TPC-DS B3. root=ASSESSMENT, permissions=[TEMPLATE] (CONTROL deliberately omitted), "
                + "query cmb_controlimplementation.",
            omittedPermClaim, "SELECT count(*) FROM " + q("cmb_controlimplementation"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-B4", "PERM", "Wrong format: 'control.view' != object type 'CONTROL' -> branch 2 array_contains fails -> 0.",
            "Mirrors TPC-DS B4. permissions=['control.view','template.view'] (dot-notation, not object "
                + "types) -- branch 2 compares against the OBJECT TYPE string 'CONTROL', not a permission string.",
            wrongFormatClaim, "SELECT count(*) FROM " + q("cmb_controlimplementation"), Expect.zero(), NEEDS_CLAIM_SWAP));

        return cs;
    }

    /**
     * Mirrors TPC-DS's R1-R4 + ODEL/OLIVE (Cases.java). OT-R1 is an honest adaptation -- see the
     * class-level note in this plan's Task 5: all 14 real cmb_v_inventoryaggregatedrisksummary
     * rows share ONE real org, so org-subtree and per-row-assignment grants always overlap on
     * this table and true additivity can't be shown without a fabricated second org.
     */
    public static List<Case> rbacGroupCases() {
        String assetsOwnerRbacClaim = Cases.claim("u.assets.owner@example.com", "SUITE_ORG", "RBAC_ABAC", "ASSETS", "[]");
        String assetsOwnerEmptyOrgClaim = Cases.claim("u.assets.owner@example.com", "SUITE_EMPTY", "RBAC_ABAC", "ASSETS", "[]");
        String assetsOwnerRbacAbacClaim = Cases.claim("u.assets.owner@example.com", "SUITE_ORG", "RBAC_ABAC", "ASSETS", "[]");
        String nobodySuiteOrgClaim = Cases.claim("u.nobody@example.com", "SUITE_ORG", "RBAC_ABAC", "ASSETS", "[]");
        String nobodyDelOrgClaim = Cases.claim("u.nobody@example.com", "DEL_ORG", "RBAC_ABAC", "ASSETS", "[]");
        String nobodyLiveOrgClaim = Cases.claim("u.nobody@example.com", "LIVE_ORG", "RBAC_ABAC", "ASSETS", "[]");

        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-R1", "RBAC",
            "RBAC_ABAC org=SUITE_ORG with an OVERLAPPING explicit assignment -> org-subtree count unaffected (10). Adapted, see class doc.",
            "TPC-DS R1 demonstrates additivity (org-subtree UNION explicit assignment > either alone). "
                + "OneTrust's single-real-org dataset means u.assets.owner's explicit assignment (900005) "
                + "is already covered by SUITE_ORG's subtree grant, so this instead proves the org-subtree "
                + "count is unaffected -- not doubled, not broken -- by a redundant per-row grant.",
            assetsOwnerRbacClaim,
            "SELECT count(*) FROM " + q("cmb_v_inventoryaggregatedrisksummary") + " WHERE upper(inventoryType) = 'ASSETS'",
            Expect.exact(10), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-R2", "RBAC",
            "RBAC_ABAC is ADDITIVE (3a OR 3b): org=SUITE_EMPTY has no children (3a empty), but the explicit assignment (3b) still shows -> 1.",
            "Mirrors TPC-DS R2. org=SUITE_EMPTY (no children seeded), so 3a's child set is empty; 3b EXISTS "
                + "still matches u.assets.owner's explicit assignment on the seeded ASSETS entity.",
            assetsOwnerEmptyOrgClaim,
            "SELECT count(*) FROM " + q("cmb_v_inventoryaggregatedrisksummary")
                + " WHERE entityID = (SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
                + " WHERE subjectId = 'u.assets.owner@example.com' LIMIT 1)",
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-R3", "RBAC", "RBAC_ABAC does not help non-root tables: 3a lives only inside root=object_type -> 0.",
            "Mirrors TPC-DS R3. mode=RBAC_ABAC, root=ASSETS, query cmb_controlimplementation (a "
                + "different, non-root table) -- branch 3 (where 3a lives) never opens for it.",
            assetsOwnerRbacAbacClaim, "SELECT count(*) FROM " + q("cmb_controlimplementation"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-R4", "RBAC", "RBAC_ABAC is org-driven: a user with NO assignment -> only branch 3a's org subtree -> 10.",
            "Mirrors TPC-DS R4. u.nobody has no assignments anywhere; mode=RBAC_ABAC, org=SUITE_ORG. "
                + "3b EXISTS finds nothing, but 3a matches all 10 real ASSETS-type rows via the org subtree "
                + "-- proves 3a is purely org-driven, independent of any grant.",
            nobodySuiteOrgClaim,
            "SELECT count(*) FROM " + q("cmb_v_inventoryaggregatedrisksummary") + " WHERE upper(inventoryType) = 'ASSETS'",
            Expect.exact(10), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-ODEL", "RBAC",
            "DEL_ORG's only child is soft-deleted -> excluded from branch 3a's child set; nobody has no assignment (3b) -> 0.",
            "Mirrors TPC-DS ODEL. org=DEL_ORG; the fixture (Task 1) seeds the real ASSETS org as a child "
                + "of DEL_ORG with isDeleted=true, so ABAC_OrgHierarchy (filtered to isDeleted IS NOT TRUE) "
                + "excludes it -- 3a's child set is empty, and u.nobody has no assignment either.",
            nobodyDelOrgClaim,
            "SELECT count(*) FROM " + q("cmb_v_inventoryaggregatedrisksummary") + " WHERE upper(inventoryType) = 'ASSETS'",
            Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-OLIVE", "RBAC",
            "Control: the SAME org is a LIVE child of LIVE_ORG -> branch 3a includes it -> 10. Proves the isDeleted flag, not emptiness, excludes OT-ODEL.",
            "Mirrors TPC-DS OLIVE. The SAME real org id is also seeded as a live child of LIVE_ORG "
                + "(isDeleted=false) -- since it's the only org all 10 real ASSETS rows carry, all 10 pass. "
                + "The only difference from OT-ODEL is the isDeleted flag, proving the flag (not emptiness) is what excludes it.",
            nobodyLiveOrgClaim,
            "SELECT count(*) FROM " + q("cmb_v_inventoryaggregatedrisksummary") + " WHERE upper(inventoryType) = 'ASSETS'",
            Expect.exact(10), NEEDS_CLAIM_SWAP));

        return cs;
    }

    /** Mirrors TPC-DS's T1-T2/O1-O2 (Cases.java) -- ctx.tenant is never read by the filter; ctx.org
     *  is read only inside the RBAC_ABAC branch (inert in plain ABAC). */
    public static List<Case> tenantOrgGroupCases() {
        String ownerClaimTenant999 = Cases.claim(999L, "u.assessment.owner@example.com", "100", "ABAC", "ASSESSMENT", "[]");
        String assetsRbacClaimTenant999 = Cases.claim(999L, "u.nobody@example.com", "SUITE_ORG", "RBAC_ABAC", "ASSETS", "[]");
        String orgUnusedAbacClaim = Cases.claim(1L, "u.assessment.owner@example.com", "ORG_UNUSED_999", "ABAC", "ASSESSMENT", "[]");
        String nobodySuiteEmptyRbacClaim = Cases.claim(1L, "u.nobody@example.com", "SUITE_EMPTY", "RBAC_ABAC", "ASSETS", "[]");

        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-T1t", "TENANT", "tenant is not read by the filter: OT-A2's claim with tenant=999 (vs tenant=1) -> identical result = 1.",
            "Mirrors TPC-DS T1. abac_row_filter never references ctx.tenant, so the tenant value cannot "
                + "affect any branch; evaluation is byte-identical to OT-A2.",
            ownerClaimTenant999,
            "SELECT count(*) FROM " + q("cmb_assessment")
                + " WHERE id = (SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
                + " WHERE subjectId = 'u.assessment.owner@example.com' AND objectType = 'ASSESSMENT' LIMIT 1)",
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-T2t", "TENANT", "tenant inert in RBAC_ABAC too: tenant=999, org=SUITE_ORG -> org still drives visibility -> same as OT-R4 = 10.",
            "Mirrors TPC-DS T2. The OT-R4 claim (mode=RBAC_ABAC, org=SUITE_ORG) but tenant=999 -- tenant "
                + "is again unread; org still drives 3a.",
            assetsRbacClaimTenant999,
            "SELECT count(*) FROM " + q("cmb_v_inventoryaggregatedrisksummary") + " WHERE upper(inventoryType) = 'ASSETS'",
            Expect.exact(10), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-O1", "ORG", "org is inert in ABAC mode (3a is the only reader, and it needs RBAC_ABAC): org=ORG_UNUSED_999 vs OT-A2's org=100 -> EXISTS unchanged -> 1.",
            "Mirrors TPC-DS O1. OT-A2's claim but org=ORG_UNUSED_999 and mode=ABAC -- ctx.org is read "
                + "ONLY inside 3a, which requires mode=RBAC_ABAC; in ABAC mode org is never consulted.",
            orgUnusedAbacClaim,
            "SELECT count(*) FROM " + q("cmb_assessment")
                + " WHERE id = (SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
                + " WHERE subjectId = 'u.assessment.owner@example.com' AND objectType = 'ASSESSMENT' LIMIT 1)",
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-O2", "ORG", "org DRIVES RBAC_ABAC: user with NO assignment + org=SUITE_EMPTY (no children) -> 3a empty AND 3b empty -> 0.",
            "Mirrors TPC-DS O2. Mirror of OT-R4 (org=SUITE_ORG -> 10); isolates the child-org set as 3a's "
                + "sole input by emptying it.",
            nobodySuiteEmptyRbacClaim,
            "SELECT count(*) FROM " + q("cmb_v_inventoryaggregatedrisksummary") + " WHERE upper(inventoryType) = 'ASSETS'",
            Expect.zero(), NEEDS_CLAIM_SWAP));

        return cs;
    }

    /** Mirrors TPC-DS's C1-C8 -- claim parsing/case-sensitivity, against the real seeded
     *  assessment (OT-A2's entity). No new SQL. */
    public static List<Case> edgeGroupCases() {
        String entitySubquery = "(SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
            + " WHERE subjectId = 'u.assessment.owner@example.com' AND objectType = 'ASSESSMENT' LIMIT 1)";
        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-C1", "EDGE", "mode 'abac' (lowercase): non-magic -> EXISTS path -> same as OT-A2.",
            "Mirrors TPC-DS C1. 'abac' is neither the magic 'DISABLE' nor 'RBAC_ABAC', so evaluation falls to 3b EXISTS.",
            Cases.claim("u.assessment.owner@example.com", "100", "abac", "ASSESSMENT", "[]"),
            "SELECT count(*) FROM " + q("cmb_assessment") + " WHERE id = " + entitySubquery,
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-C2", "EDGE", "mode 'disable' (lowercase): NOT allow-all (DISABLE is case-sensitive) -> 1.",
            "Mirrors TPC-DS C2. Branch 1 compares ctx.mode = 'DISABLE' case-SENSITIVELY.",
            Cases.claim("u.assessment.owner@example.com", "100", "disable", "ASSESSMENT", "[]"),
            "SELECT count(*) FROM " + q("cmb_assessment") + " WHERE id = " + entitySubquery,
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-C3", "EDGE", "root 'assessment' (lowercase) != 'ASSESSMENT' -> root branch fails -> 0.",
            "Mirrors TPC-DS C3. Branch 3's gate 'ctx.root = object_type' is case-sensitive.",
            Cases.claim("u.assessment.owner@example.com", "100", "ABAC", "assessment", "[]"),
            "SELECT count(*) FROM " + q("cmb_assessment"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-C4", "EDGE", "missing 'permissions': from_json null; root path unaffected -> 1.",
            "Mirrors TPC-DS C4. The root/3b path never touches permissions.",
            "{\"tenant\":1,\"user\":\"u.assessment.owner@example.com\",\"org\":\"100\",\"mode\":\"ABAC\",\"root\":\"ASSESSMENT\"}",
            "SELECT count(*) FROM " + q("cmb_assessment") + " WHERE id = " + entitySubquery,
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-C5", "EDGE", "extra unknown field 'scope' ignored by from_json -> 1.",
            "Mirrors TPC-DS C5. from_json drops fields not in the target STRUCT.",
            "{\"tenant\":1,\"user\":\"u.assessment.owner@example.com\",\"org\":\"100\",\"mode\":\"ABAC\",\"root\":\"ASSESSMENT\",\"permissions\":[],\"scope\":\"xyz\"}",
            "SELECT count(*) FROM " + q("cmb_assessment") + " WHERE id = " + entitySubquery,
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-C6", "EDGE", "tenant as string \"1\": from_json tolerates the type mismatch; row set unchanged -> 1.",
            "Mirrors TPC-DS C6.",
            "{\"tenant\":\"1\",\"user\":\"u.assessment.owner@example.com\",\"org\":\"100\",\"mode\":\"ABAC\",\"root\":\"ASSESSMENT\",\"permissions\":[]}",
            "SELECT count(*) FROM " + q("cmb_assessment") + " WHERE id = " + entitySubquery,
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-C7", "EDGE", "empty claim {}: all fields null -> secure default deny -> 0.",
            "Mirrors TPC-DS C7. A malformed/empty claim fails closed.",
            "{}", "SELECT count(*) FROM " + q("cmb_assessment"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-C8", "EDGE", "user mixed-case: exact subjectId compare fails -> 0.",
            "Mirrors TPC-DS C8. Identities are matched exactly, case included.",
            Cases.claim("U.Assessment.Owner@example.com", "100", "ABAC", "ASSESSMENT", "[]"),
            "SELECT count(*) FROM " + q("cmb_assessment"), Expect.zero(), NEEDS_CLAIM_SWAP));

        return cs;
    }

    /** Mirrors TPC-DS's W1/WP1/WP2/WS1 -- UC_ABAC_MULTIPLE_ROW_FILTERS, table-wide, regardless of
     *  column bindings. Setup: sql_onetrust/08_row_filter_conflict.sql (isolated schema). */
    public static List<Case> conflictGroupCases() {
        String schema = "abac_onetrust.abac_conflict";
        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-W1", "CONFLICT", "Two policies on conflict_a (allow-all + deny-all): UC rejects the query -- at most one row filter per table.",
            "Mirrors TPC-DS W1. Setup: sql_onetrust/08_row_filter_conflict.sql.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".conflict_a",
            Expect.errorContains("UC_ABAC_MULTIPLE_ROW_FILTERS"), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-WP1", "CONFLICT", "conflict_b count(*): two row filters with DIFFERENT bindings -> at most one row filter per table.",
            "Mirrors TPC-DS WP1.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".conflict_b",
            Expect.errorContains("UC_ABAC_MULTIPLE_ROW_FILTERS"), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-WP2", "CONFLICT", "conflict_b SELECT col1: this column is bound by rf_b_1 ONLY, yet still errors -- the conflict is table-wide.",
            "Mirrors TPC-DS WP2. The conflict is detected at the TABLE level during planning, before any column-specific evaluation.",
            Cases.DISABLE_CLAIM, "SELECT col1 FROM " + schema + ".conflict_b",
            Expect.errorContains("UC_ABAC_MULTIPLE_ROW_FILTERS"), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-WS1", "CONFLICT", "conflict_c count(*): two row filters on the SAME column -> at most one row filter per table.",
            "Mirrors TPC-DS WS1.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".conflict_c",
            Expect.errorContains("UC_ABAC_MULTIPLE_ROW_FILTERS"), NEEDS_CLAIM_SWAP));

        return cs;
    }

    /** Mirrors TPC-DS's N1-N4 -- onboarding a new table under the SAME deployed row filter.
     *  Setup: sql_onetrust/09_onboard_new_tables.sql (isolated tables, real shared metadata). */
    public static List<Case> metaGroupCases() {
        String metaSchema = "abac_onetrust.abac_meta";
        String promoClaim = Cases.claim("u.meta.tester@example.com", "100", "ABAC", "META_PROMO", "[]");
        String storeClaim = Cases.claim("u.meta.tester@example.com", "100", "ABAC", "META_STORE", "[]");
        String ccClaim = Cases.claim("u.meta.tester@example.com", "100", "ABAC", "META_CC", "[]");
        String shipClaim = Cases.claim("u.meta.tester@example.com", "100", "ABAC", "META_SHIP", "[]");

        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-N1", "META", "meta_promo: its esa row has isDeleted=true -> excluded -> 0 (negative).",
            "Mirrors TPC-DS N1. Setup: sql_onetrust/09_onboard_new_tables.sql.",
            promoClaim, "SELECT count(*) FROM " + metaSchema + ".meta_promo WHERE id = 1", Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-N2", "META", "meta_store: esa subjectType=USER_GROUP; meta.tester is a member -> group path grants -> 1 (positive).",
            "Mirrors TPC-DS N2. Proves the group-membership grant path AND that a brand-new table onboards correctly.",
            storeClaim, "SELECT count(*) FROM " + metaSchema + ".meta_store WHERE id = 1", Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-N3", "META", "meta_cc: its ABAC_Assignment has isActive=false -> the JOIN ... AND a.isActive fails -> 0 (negative).",
            "Mirrors TPC-DS N3.",
            ccClaim, "SELECT count(*) FROM " + metaSchema + ".meta_cc WHERE id = 1", Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-N4", "META", "meta_ship: its ABAC_Assignment has isDeleted=true -> the AND a.isDeleted=false fails -> 0 (negative).",
            "Mirrors TPC-DS N4.",
            shipClaim, "SELECT count(*) FROM " + metaSchema + ".meta_ship WHERE id = 1", Expect.zero(), NEEDS_CLAIM_SWAP));

        return cs;
    }

    /** Mirrors TPC-DS's TH1-TH3 -- a SEPARATE range (>=) row filter, isolated table but real
     *  shared metadata. Setup: sql_onetrust/10_threshold_filter.sql. */
    public static List<Case> threshGroupCases() {
        String schema = "abac_onetrust.abac_thresh";
        String claim = Cases.claim("u.thresh.tester@example.com", "100", "ABAC", "THRESH_INVENTORY", "[]");

        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-TH1", "THRESH", "Range grant: tester assigned 250 -> rows with quantity >= 250 are visible -> 11 rows.",
            "Mirrors TPC-DS TH1. Setup: sql_onetrust/10_threshold_filter.sql (quantity = id*25, 20 rows).",
            claim, "SELECT count(*) FROM " + schema + ".thresh_inventory", Expect.exact(11), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-TH2", "THRESH", "The cutoff holds: among VISIBLE rows, none are below the threshold -> count where quantity < 250 is exactly 0.",
            "Mirrors TPC-DS TH2. Data-independent: the row filter is ANDed with the query, so "
                + "'quantity >= 250 AND quantity < 250' is impossible for every row.",
            claim, "SELECT count(*) FROM " + schema + ".thresh_inventory WHERE quantity < 250", Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-TH3", "THRESH", "The floor holds: the minimum visible quantity is >= 250 (asserted; expected: exactly 250).",
            "Mirrors TPC-DS TH3. Confirms the boundary the '>=' predicate enforces.",
            claim, "SELECT min(quantity) FROM " + schema + ".thresh_inventory", Expect.atLeast(250), NEEDS_CLAIM_SWAP));

        return cs;
    }

    /** Mirrors TPC-DS's DR1 -- classic RLS, no tags, no policy. Setup:
     *  sql_onetrust/11_direct_rls_and_dr2.sql (which also sets up DR2 for OnetrustDr2HotSwap, Task 18). */
    public static List<Case> rlsGroupCases() {
        List<Case> cs = new ArrayList<>();
        cs.add(new Case("OT-DR1", "RLS",
            "Direct classic RLS (NO tags, NO policy): rls_demo has SET ROW FILTER keeping id >= 10 -> count where < 10 is 0.",
            "Mirrors TPC-DS DR1. Setup: sql_onetrust/11_direct_rls_and_dr2.sql. Data-independent proof "
                + "that classic (table-managed) RLS filters WITHOUT any ABAC tag/policy machinery -- "
                + "contrast OnetrustDr2HotSwap (Task 18), which does the same via a has_tag() policy.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM abac_onetrust.abac_rls.rls_demo WHERE id < 10",
            Expect.zero(), NEEDS_CLAIM_SWAP));
        return cs;
    }

    /** Mirrors TPC-DS's V1-V3 -- row filters (classic and ABAC) propagate through views, including
     *  aggregates. Setup: sql_onetrust/12_views.sql (requires Task 10's SQL applied first). */
    public static List<Case> viewGroupCases() {
        String schema = "abac_onetrust.abac_rls";
        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-V1", "V", "View over a governed base table (classic RLS) inherits the base row filter.",
            "Mirrors TPC-DS V1. Setup: sql_onetrust/12_views.sql.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".v_rls_demo_governed WHERE id < 10",
            Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-V2", "V", "View over a table governed by an ABAC policy still filters.",
            "Mirrors TPC-DS V2.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".v_dr2_demo_governed",
            Expect.exact(10), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-V3", "V", "Aggregate through a view cannot leak filtered rows.",
            "Mirrors TPC-DS V3. min(id) through the view must be >= 10 -- an aggregate must not "
                + "reveal filtered-out rows exist.",
            Cases.DISABLE_CLAIM, "SELECT min(id) FROM " + schema + ".v_rls_demo_governed",
            Expect.atLeast(10), NEEDS_CLAIM_SWAP));

        return cs;
    }

    /** Mirrors TPC-DS's SC1-SC4 -- ON SCHEMA policy scope. Setup: sql_onetrust/13_policy_scope.sql. */
    public static List<Case> scGroupCases() {
        String schema = "abac_onetrust.abac_scope";
        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-SC1", "SC", "ON SCHEMA policy governs a table in that schema",
            "Mirrors TPC-DS SC1. Setup: sql_onetrust/13_policy_scope.sql.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".scoped_a", Expect.exact(10), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-SC2", "SC", "ON SCHEMA policy covers EVERY matching member, not just the first",
            "Mirrors TPC-DS SC2. scoped_c is a THIRD table in the same schema, covered by nothing but "
                + "the schema-level policy.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".scoped_c WHERE id > 10", Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-SC3", "SC", "A table with no matching tag is NOT governed -- returns ALL rows",
            "Mirrors TPC-DS SC3. ungoverned sits inside the policy's ON SCHEMA scope but has no "
                + "abac_column_id tag -- the dangerous fail-open case.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".ungoverned", Expect.exact(20), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-SC4", "SC", "Schema-level + table-level row filters CONFLICT -- they do not have a precedence order",
            "Mirrors TPC-DS SC4. scoped_b is covered by both the schema-level and a table-level policy.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".scoped_b",
            Expect.errorContains("UC_ABAC_MULTIPLE_ROW_FILTERS"), NEEDS_CLAIM_SWAP));

        return cs;
    }

    /** Mirrors TPC-DS's TG1-TG3 -- tag-binding edge cases. Setup: sql_onetrust/14_tag_binding.sql.
     *  OT-TG2 ships as INFO, same as TPC-DS's TG2 originally did, pending an OneTrust-side live
     *  observation of which column (if either) Databricks actually binds. */
    public static List<Case> tgGroupCases() {
        String schema = "abac_onetrust.abac_tags";
        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-TG1", "TG", "has_tag_value() binds only the column whose tag VALUE matches",
            "Mirrors TPC-DS TG1. Setup: sql_onetrust/14_tag_binding.sql.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".tagval", Expect.exact(10), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-TG2", "TG", "Two columns sharing one tag -- record which column (if either) Databricks binds",
            "Mirrors TPC-DS TG2. INFO until observed live on abac_onetrust -- TPC-DS's own TG2 found "
                + "Databricks REFUSES to bind (UC_ABAC_AMBIGUOUS_COLUMN_MATCH), not that it silently "
                + "picks the first column; confirm the same holds here before converting to a hard assertion.",
            Cases.DISABLE_CLAIM, "SELECT a FROM " + schema + ".dualtag ORDER BY a", Expect.info(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-TG3", "TG", "A MATCH COLUMNS that matches nothing makes the policy SILENTLY not apply",
            "Mirrors TPC-DS TG3. abac_column_org (registered) matches no column on notag -- fails OPEN.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".notag", Expect.exact(20), NEEDS_CLAIM_SWAP));

        return cs;
    }

    /** Mirrors TPC-DS's UC2 -- a declared-type UDF param bound to a differently-typed column is
     *  coerced, not rejected. Setup: sql_onetrust/15_udf_contract.sql. UC1 has no case -- see the
     *  class doc there and Task 16's DP1 note. */
    public static List<Case> ucGroupCases() {
        List<Case> cs = new ArrayList<>();
        cs.add(new Case("OT-UC2", "UC", "Declared DATE param vs bound TIMESTAMP column -- Databricks COERCES, it does not reject",
            "Mirrors TPC-DS UC2. Setup: sql_onetrust/15_udf_contract.sql.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM abac_onetrust.abac_udf.arity",
            Expect.exact(9), NEEDS_CLAIM_SWAP));
        return cs;
    }

    /** Mirrors TPC-DS's XT1 -- the one-row-filter-per-table limit spans both ABAC and classic RLS.
     *  Setup: sql_onetrust/16_cross_mechanism.sql. */
    public static List<Case> xtGroupCases() {
        List<Case> cs = new ArrayList<>();
        cs.add(new Case("OT-XT1", "XT", "Classic SET ROW FILTER + ABAC policy on the SAME table",
            "Mirrors TPC-DS XT1. Setup: sql_onetrust/16_cross_mechanism.sql. abac_fn keeps id<=10; "
                + "classic_fn keeps id>15 -- disjoint predicates make every outcome diagnostic (see the "
                + "decode table in the SQL file's comments).",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM abac_onetrust.abac_xmech.both",
            Expect.errorContains("UC_ABAC_MULTIPLE_ROW_FILTERS"), NEEDS_CLAIM_SWAP));
        return cs;
    }

    /** Mirrors TPC-DS's EX1/EX2 -- the TO ... EXCEPT exemption + its control. Setup:
     *  sql_onetrust/17_except_and_defaults.sql. DP1 has no case -- DDL-time rejection, not
     *  suite-observable, see the class doc there. */
    public static List<Case> exGroupCases() {
        String schema = "abac_onetrust.abac_gaps";
        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-EX2", "EX", "CONTROL for OT-EX1: the SP IS subject to a broad TO with no EXCEPT -- filtered to 10",
            "Mirrors TPC-DS EX2. Setup: sql_onetrust/17_except_and_defaults.sql. Must run/be read "
                + "BEFORE OT-EX1 -- OT-EX1's result is only meaningful if this returns 10.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".subject", Expect.exact(10), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-EX1", "EX", "EXCEPT clause: the excepted principal is NOT subject to the policy -- sees ALL rows",
            "Mirrors TPC-DS EX1. exempt_policy is bound TO `account users` EXCEPT the SP.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".exempt", Expect.exact(20), NEEDS_CLAIM_SWAP));

        return cs;
    }

    /** Mirrors TPC-DS's CL1-CL4 -- malformed/null-shaped claim JSON. No DDL; pure claim-shape
     *  variation against the real seeded assignment (Task 2). */
    public static List<Case> clGroupCases() {
        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-CL1", "CL", "Claim missing the `mode` key entirely, sent by a user with NO assignment",
            "Mirrors TPC-DS CL1. from_json produces ctx.mode = NULL -- branch 1 (mode='DISABLE') and "
                + "3a (mode='RBAC_ABAC') are both unreadable without a mode, regardless of who ctx.user "
                + "is; this user has no assignment either, so 3b also fails.",
            "{\"tenant\":1,\"user\":\"u.cl.nobody@example.com\",\"org\":\"100\",\"root\":\"ASSESSMENT\",\"permissions\":[]}",
            "SELECT count(*) FROM " + q("cmb_assessment"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-CL2", "CL", "Claim with an explicit null user",
            "Mirrors TPC-DS CL2. ctx.user = NULL, so the 3b subject match (esa.subjectId = ctx.user) "
                + "is NULL for every row and no assignment can match.",
            "{\"tenant\":1,\"user\":null,\"org\":\"100\",\"mode\":\"ABAC\",\"root\":\"ASSESSMENT\",\"permissions\":[]}",
            "SELECT count(*) FROM " + q("cmb_assessment"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-CL3", "CL", "Claim with `permissions` as a string instead of an array",
            "Mirrors TPC-DS CL3. The declared struct type is ARRAY<STRING>; a scalar string is not "
                + "coercible, so ctx.permissions is NULL and array_contains(NULL, ...) is NULL -- "
                + "branch 2 cannot fire. root=ASSESSMENT querying cmb_template (non-root) means only "
                + "branch 2 could have granted access.",
            "{\"tenant\":1,\"user\":\"u.assessment.owner@example.com\",\"org\":\"100\",\"mode\":\"ABAC\",\"root\":\"ASSESSMENT\",\"permissions\":\"TEMPLATE\"}",
            "SELECT count(*) FROM " + q("cmb_template"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-CL4", "CL", "Claim with a null ELEMENT inside `permissions` (not covered by CL1-CL3)",
            "Mirrors TPC-DS CL4. permissions=[null,\"CONTROL\"] -- INFO until observed: does a null "
                + "element alongside a real match break array_contains's ability to find 'CONTROL', or "
                + "does it still correctly fire branch 2? root=ASSESSMENT querying cmb_controlimplementation "
                + "(non-root) isolates branch 2 as the only path that could grant access.",
            "{\"tenant\":1,\"user\":\"u.assessment.owner@example.com\",\"org\":\"100\",\"mode\":\"ABAC\",\"root\":\"ASSESSMENT\",\"permissions\":[null,\"CONTROL\"]}",
            "SELECT count(*) FROM " + q("cmb_controlimplementation"), Expect.info(), NEEDS_CLAIM_SWAP));

        return cs;
    }

    /**
     * The 50 real compatible queries, run as INFO cases (execution success checked, row counts
     * not asserted) -- the first time they run under real row-filter enforcement instead of owner
     * bypass. IDs are short (OTQ01..OTQ50); the full query_alias hash from the CSV goes in the
     * description for traceability back to onetrust_sanity_run_annotated.csv.
     *
     * Uses the SAME claim as OT-T8 (RBAC_ABAC, root=ASSETS, org=RBAC_ORG_ID), not the
     * ASSESSMENT/TEMPLATE owner claim: 39 of the 50 queries touch
     * cmb_v_inventoryaggregatedrisksummary, whose real object types (ASSETS/VENDORS/
     * PROCESSING-ACTIVITIES) the owner claim has no visibility into at all -- every one of those
     * 39 came back empty under it (confirmed on a live run). OT-T8 already proves this RBAC claim
     * returns real, non-empty rows against that exact table, and RBAC_ORG_ID is the literal org id
     * several of the real queries filter on directly (e.g. "WHERE main.parentOrgID = '<RBAC_ORG_ID>'"),
     * so this claim is expected to surface real matching data for most of those 39. The remaining
     * 11 (9 EntityGroupConfig -- 0 rows in the dataset regardless of claim; 2 CMB_Assessment/
     * OrgHierarchy -- filter on hardcoded real-customer org/user ids absent from our synthetic
     * seed) stay empty regardless of which claim is used here; that's a data-scope limit, not a
     * claim problem.
     */
    public static List<Case> compatibleQueryCases() {
        String rbacClaim = Cases.claim("u.rbac.viewer@example.com", RBAC_ORG_ID, "RBAC_ABAC", "ASSETS", "[]");
        List<Case> cs = new ArrayList<>();
        int i = 0;
        for (CSVRecord row : loadAnnotatedQueries()) {
            if (!"yes".equals(row.get("in_scope"))) continue;
            i++;
            cs.add(new Case(String.format("OTQ%02d", i), "ONETRUST-Q",
                "real compatible query, run as the SP under a live claim",
                "query_alias=" + row.get("query_alias") + " tables_used=" + row.get("tables_used"),
                rbacClaim, row.get("modified_query"), Expect.info(), NEEDS_CLAIM_SWAP));
        }
        return cs;
    }

    private static List<CSVRecord> loadAnnotatedQueries() {
        // Matches the documented invocation ("cd JDBC && mvn ...", "java -jar target/...") --
        // working directory is JDBC/, so the CSV is one level up.
        Path csvPath = Paths.get("..", "onetrust", "onetrust_sanity_run_annotated.csv");
        if (!Files.exists(csvPath)) {
            throw new IllegalStateException(
                "Cannot find " + csvPath.toAbsolutePath()
                    + " -- run from the JDBC/ directory (cd JDBC && java -jar target/...).");
        }
        try (Reader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.parse(reader,
                 CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build())) {
            return parser.getRecords();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + csvPath.toAbsolutePath(), e);
        }
    }
}
