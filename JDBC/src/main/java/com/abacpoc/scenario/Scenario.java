package com.abacpoc.scenario;

import com.abacpoc.engine.Capability;
import com.abacpoc.engine.Engine;

import java.sql.Connection;
import java.util.Set;

/** A multi-step test that a single query + Expect cannot express (state changes, timing,
 *  multiple connections). Returns {pass, fail, skip, error}. */
public interface Scenario {
    String id();
    Set<Capability> requires();
    int[] run(Engine e, Connection c);
}
