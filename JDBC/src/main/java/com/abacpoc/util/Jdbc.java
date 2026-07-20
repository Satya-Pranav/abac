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
}
