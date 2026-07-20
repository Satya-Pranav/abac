package com.abacpoc.cases;

import java.util.List;

/** An expected outcome. ALL = every row the query would return unfiltered (measured via DISABLE);
 *  ATLEAST = the scalar result is >= n; IDLIST = the projected first column equals `ids` exactly
 *  (order-sensitive); ERR = the query is expected to FAIL with a message containing `text`. */
public final class Expect {
    public enum Kind { ALL, ZERO, NONZERO, EXACT, ATLEAST, IDLIST, INFO, ERR }

    public final Kind kind; public final long n; public final String text; public final List<String> ids;
    private Expect(Kind k, long n, String text, List<String> ids) {
        this.kind = k; this.n = n; this.text = text; this.ids = ids;
    }
    public static Expect all()                   { return new Expect(Kind.ALL, 0, null, null); }
    public static Expect zero()                  { return new Expect(Kind.ZERO, 0, null, null); }
    public static Expect nonzero()               { return new Expect(Kind.NONZERO, 0, null, null); }
    public static Expect exact(long n)           { return new Expect(Kind.EXACT, n, null, null); }
    public static Expect atLeast(long n)         { return new Expect(Kind.ATLEAST, n, null, null); }
    public static Expect exactIds(String... v)   { return new Expect(Kind.IDLIST, 0, null, List.of(v)); }
    public static Expect info()                  { return new Expect(Kind.INFO, 0, null, null); }
    public static Expect errorContains(String s) { return new Expect(Kind.ERR, 0, s, null); }
    public String describe() {
        switch (kind) {
            case ALL:     return "ALL rows";
            case ZERO:    return "0 rows";
            case NONZERO: return ">0 rows";
            case EXACT:   return n + (n == 1 ? " row" : " rows");
            case ATLEAST: return "value >= " + n;
            case IDLIST:  return "ids exactly " + ids;
            case ERR:     return "query ERROR containing \"" + text + "\"";
            default:      return "(informational — printed, not checked)";
        }
    }
}
