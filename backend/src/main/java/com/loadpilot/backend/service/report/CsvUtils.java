package com.loadpilot.backend.service.report;

/** Echappement CSV (RFC 4180) et protection contre l'injection de formules dans Excel. */
public final class CsvUtils {

    private CsvUtils() {
    }

    public static String field(Object value) {
        if (value == null) {
            return "";
        }
        String raw = value.toString();
        if (!raw.isEmpty() && "=+-@".indexOf(raw.charAt(0)) >= 0) {
            raw = "'" + raw;
        }
        boolean needsQuoting = raw.contains(",") || raw.contains("\"") || raw.contains("\n") || raw.contains("\r");
        String escaped = raw.replace("\"", "\"\"");
        return needsQuoting ? "\"" + escaped + "\"" : escaped;
    }
}
