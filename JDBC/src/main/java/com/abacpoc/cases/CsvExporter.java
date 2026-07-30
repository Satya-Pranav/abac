// JDBC/src/main/java/com/abacpoc/cases/CsvExporter.java
package com.abacpoc.cases;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Exports OnetrustCases.all() to a CSV matching query_shortlist.write_shortlist_csv's schema
 * (query_id,source,tables_used,claim,query,expected_or_observed,verified_status), so this file
 * and the Python-produced real-query shortlist concatenate into one final deliverable CSV
 * (design doc section 8). "tables_used" is left blank here -- Case has no structured table
 * field, only an id/group/sql/claim; the query text itself names the table(s).
 *
 * SQL text is rewritten from the original abac_onetrust catalog to abac_onetrust_scale via a
 * literal substring replace: every OnetrustCases query is built through the q(String) helper,
 * which always prefixes with the fixed SCHEMA constant "abac_onetrust.onetrust_sim" -- so the
 * replacement is exact and total, not a heuristic.
 */
public final class CsvExporter {

    private static final String ORIGINAL_CATALOG_SCHEMA = "abac_onetrust.onetrust_sim";
    private static final String TARGET_CATALOG_SCHEMA = "abac_onetrust_scale.onetrust_sim";

    private CsvExporter() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: CsvExporter <output.csv>");
            System.exit(1);
        }
        Path out = Paths.get(args[0]);
        List<Case> cases = OnetrustCases.all();

        try (PrintWriter w = new PrintWriter(Path.of(out.toString()).toFile(), "UTF-8")) {
            w.println("query_id,source,tables_used,claim,query,expected_or_observed,verified_status");
            for (Case c : cases) {
                String query = c.sql().replace(ORIGINAL_CATALOG_SCHEMA, TARGET_CATALOG_SCHEMA);
                w.println(String.join(",",
                    csvField(c.id()),
                    csvField("functional_test"),
                    csvField(""),
                    csvField(c.claim()),
                    csvField(query),
                    csvField(c.exp().describe()),
                    csvField("")
                ));
            }
        }
        System.out.println("Wrote " + cases.size() + " functional-test rows to " + out);
    }

    private static String csvField(String value) {
        String v = value == null ? "" : value;
        String escaped = v.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
