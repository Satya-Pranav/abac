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
     * The 50 real compatible queries, run as INFO cases (execution success checked, row counts
     * not asserted) under the seeded owner claim -- the first time they run under real row-filter
     * enforcement instead of owner bypass. IDs are short (OTQ01..OTQ50); the full query_alias hash
     * from the CSV goes in the description for traceability back to onetrust_sanity_run_annotated.csv.
     */
    public static List<Case> compatibleQueryCases() {
        String ownerClaim = Cases.claim("u.assessment.owner@example.com", "100", "ABAC", "ASSESSMENT", "[\"TEMPLATE\"]");
        List<Case> cs = new ArrayList<>();
        int i = 0;
        for (CSVRecord row : loadAnnotatedQueries()) {
            if (!"yes".equals(row.get("in_scope"))) continue;
            i++;
            cs.add(new Case(String.format("OTQ%02d", i), "ONETRUST-Q",
                "real compatible query, run as the SP under a live claim",
                "query_alias=" + row.get("query_alias") + " tables_used=" + row.get("tables_used"),
                ownerClaim, row.get("modified_query"), Expect.info(), NEEDS_CLAIM_SWAP));
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
