package com.abacpoc.engine;

/** Engine features a case may require. A case whose requirements are not met reports SKIP. */
public enum Capability {
    POLICY_DDL,    // CREATE POLICY ... ROW FILTER
    CLAIM_SWAP,    // per-statement identity injection
    TAGS,          // governed column tags + has_tag()
    CLASSIC_RLS,   // ALTER TABLE ... SET ROW FILTER
    VIEWS,         // views over governed base tables
    SCHEMA_SCOPE,  // ON SCHEMA policy scoping
    DML            // INSERT/UPDATE/DELETE against ordinary tables (fixture seed/teardown)
}
