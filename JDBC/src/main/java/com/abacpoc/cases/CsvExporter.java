// JDBC/src/main/java/com/abacpoc/cases/CsvExporter.java
package com.abacpoc.cases;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Exports OnetrustCases' functional-test cases to a CSV matching
 * query_shortlist.write_shortlist_csv's schema (query_id,source,tables_used,claim,query,
 * expected_or_observed,verified_status), so this file and the Python-produced real-query
 * shortlist concatenate into one final deliverable CSV (design doc section 8). "tables_used" is
 * left blank here -- Case has no structured table field, only an id/group/sql/claim; the query
 * text itself names the table(s).
 *
 * Case source is OnetrustCases.all() (the ~119 cases valid against the original abac_onetrust
 * catalog) concatenated with OnetrustCases.newGovernedTableCases() (the 8 scale-2-only cases
 * deliberately excluded from all() -- see that method's comment -- because all() also runs
 * as-is against the untouched abac_onetrust catalog via Runner.runOnetrustCasesOn(), which has
 * neither the 4 newly-governed tables nor their seed data). Both halves get the same
 * catalog-name rewrite below, so the exclusion from all() doesn't change what ships in the CSV.
 *
 * OTQ* cases (OnetrustCases.compatibleQueryCases(), the 50 real compatible queries) are
 * deliberately SKIPPED here: those same 50 SQL texts are independently re-added as "real_query"
 * rows by onetrust_synth/query_shortlist.py, each paired with its own
 * SEEDED_CLAIMS_BY_TABLE-derived claim. Exporting both halves would put the same query in the
 * final assembled CSV twice under two DIFFERENT claims, contradicting "each query gets tested
 * under one claim" (design doc section 7/8) and double-counting any pass-rate computed over the
 * CSV. query_shortlist.py's real_query rows are the sole source for those 50 queries in the
 * final CSV -- they carry observed row counts and PASS/FAIL status, which compatibleQueryCases's
 * INFO/atLeast/zero expectations don't. compatibleQueryCases() itself is untouched and still
 * available on OnetrustCases for non-CSV-export purposes (e.g. running under Runner.java).
 *
 * SQL text is rewritten from the original abac_onetrust catalog to abac_onetrust_scale via
 * literal substring replacement for the two production schemas that exist in both catalogs:
 * - abac_onetrust.onetrust_sim → abac_onetrust_scale.onetrust_sim (majority of queries)
 * - abac_onetrust.monitoring → abac_onetrust_scale.monitoring (9 real queries: OTQ02, OTQ04, OTQ11, OTQ30, OTQ32, OTQ33, OTQ40, OTQ41, OTQ49)
 *   -- moot now that OTQ* rows are skipped, kept for the (currently zero) case a future
 *   non-OTQ case references the monitoring schema.
 *
 * Note: ~26 rows reference throwaway mechanism-test schemas (abac_conflict, abac_meta,
 * abac_thresh, abac_rls, abac_scope, abac_tags, abac_udf, abac_xmech, abac_gaps, etc.)
 * which are intentionally LEFT UNREWRITTEN as they don't exist in abac_onetrust_scale.
 */
public final class CsvExporter {

    private static final String ONETRUST_SIM_ORIGINAL = "abac_onetrust.onetrust_sim";
    private static final String ONETRUST_SIM_TARGET = "abac_onetrust_scale.onetrust_sim";
    private static final String MONITORING_ORIGINAL = "abac_onetrust.monitoring";
    private static final String MONITORING_TARGET = "abac_onetrust_scale.monitoring";

    private CsvExporter() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: CsvExporter <output.csv>");
            System.exit(1);
        }
        Path out = Paths.get(args[0]);
        List<Case> cases = new ArrayList<>(OnetrustCases.all());
        cases.addAll(OnetrustCases.newGovernedTableCases());

        int written = 0;
        try (PrintWriter w = new PrintWriter(Path.of(out.toString()).toFile(), "UTF-8")) {
            w.println("query_id,source,tables_used,claim,query,expected_or_observed,verified_status");
            for (Case c : cases) {
                // See class javadoc: the 50 OTQ* real-query cases are exported solely via
                // query_shortlist.py's real_query rows, not from here.
                if (c.id().startsWith("OTQ")) continue;
                String query = c.sql()
                    .replace(ONETRUST_SIM_ORIGINAL, ONETRUST_SIM_TARGET)
                    .replace(MONITORING_ORIGINAL, MONITORING_TARGET);
                w.println(String.join(",",
                    csvField(c.id()),
                    csvField("functional_test"),
                    csvField(""),
                    csvField(c.claim()),
                    csvField(query),
                    csvField(c.exp().describe()),
                    csvField("")
                ));
                written++;
            }
        }
        System.out.println("Wrote " + written + " functional-test rows to " + out
            + " (" + cases.size() + " candidate cases, " + (cases.size() - written) + " OTQ* skipped)");
    }

    private static String csvField(String value) {
        String v = value == null ? "" : value;
        String escaped = v.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
