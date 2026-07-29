package com.abacpoc.cases;

import com.abacpoc.engine.Capability;
import com.abacpoc.engine.Engine;

import java.util.Map;
import java.util.Set;

/** exp = the expected outcome under the deployed full 3-branch abac_row_filter.
 *
 *  expectOverrides: keyed by Engine.name() (e.g. "e6data"), for the rare case where a DIFFERENT
 *  engine surfaces the SAME underlying condition through different observable behavior -- e.g. a
 *  conflicting-policy table makes Databricks raise UC_ABAC_MULTIPLE_ROW_FILTERS but makes e6data
 *  raise its own ABAC_FAIL_CLOSED text. Both are the engine correctly refusing an unresolvable
 *  policy; the assertion just needs engine-specific wording. Empty by default -- the vast majority
 *  of cases assert the SAME outcome on every engine, so expectFor() falls back to exp(). */
public record Case(String id, String group, String purpose, String description,
                   String claim, String sql, Expect exp, Set<Capability> requires,
                   Map<String, Expect> expectOverrides) {

    /** Convenience for cases needing an engine-specific override. */
    public Case(String id, String group, String purpose, String description,
                String claim, String sql, Expect exp, Set<Capability> requires,
                String overrideEngine, Expect overrideExpect) {
        this(id, group, purpose, description, claim, sql, exp, requires,
             Map.of(overrideEngine, overrideExpect));
    }

    /** Convenience for the existing cases, which all require the Databricks feature set. */
    public Case(String id, String group, String purpose, String description,
                String claim, String sql, Expect exp, Set<Capability> requires) {
        this(id, group, purpose, description, claim, sql, exp, requires, Map.of());
    }

    /** Convenience for the existing cases, which all require the Databricks feature set. */
    public Case(String id, String group, String purpose, String description,
                String claim, String sql, Expect exp) {
        this(id, group, purpose, description, claim, sql, exp, Set.of(), Map.of());
    }

    /** The expectation to assert for {@code engine}, honoring any engine-specific override. */
    public Expect expectFor(Engine engine) {
        return expectOverrides.getOrDefault(engine.name(), exp);
    }
}
