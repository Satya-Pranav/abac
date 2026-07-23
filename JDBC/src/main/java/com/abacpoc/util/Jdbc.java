package com.abacpoc.util;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Suite-agnostic JDBC helpers shared by the orchestrator (Runner) and Scenario implementations. */
public final class Jdbc {

    private Jdbc() {}

    public static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) { st.execute(sql); }
    }

    public static long count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public static List<String> firstColumn(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    /** Collapse a huge multi-line driver error into one readable line. */
    public static String shortErr(String msg) {
        if (msg == null) return "(no message)";
        String s = msg.replaceAll("\\s+", " ").trim();
        return s.length() > 260 ? s.substring(0, 260) + " …" : s;
    }

    /**
     * Poll a scalar count query until it returns {@code target}, or until {@code timeoutMs} elapses,
     * checking every {@code intervalMs}. Returns the elapsed milliseconds at the moment the target
     * was first observed, or -1 if it never reached the target within the timeout.
     *
     * <p>Purpose: MEASURE how long a policy / UDF change takes to become visible, instead of waiting
     * a fixed sleep and hoping. The caller asserts that the value WAS reached (a correctness check);
     * the returned latency is reported, NEVER asserted against a threshold — elapsed time depends on
     * cluster load and is not a pass/fail criterion. A -1 return IS a correctness failure (the change
     * never propagated within a generous bound), and the caller treats it as such.
     */
    public static long pollUntilCount(Connection c, String sql, long target,
                                      long timeoutMs, long intervalMs) throws SQLException {
        long start = System.nanoTime();
        while (true) {
            long v = count(c, sql);
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            if (v == target) return elapsed;
            if (elapsed >= timeoutMs) return -1;
            try { Thread.sleep(intervalMs); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return -1; }
        }
    }
}
