package com.abacpoc.cases;

import com.abacpoc.engine.Capability;

import java.util.Set;

/** exp = the expected outcome under the deployed full 3-branch abac_row_filter. */
public record Case(String id, String group, String purpose, String description,
                   String claim, String sql, Expect exp, Set<Capability> requires) {

    /** Convenience for the existing cases, which all require the Databricks feature set. */
    public Case(String id, String group, String purpose, String description,
                String claim, String sql, Expect exp) {
        this(id, group, purpose, description, claim, sql, exp, Set.of());
    }
}
