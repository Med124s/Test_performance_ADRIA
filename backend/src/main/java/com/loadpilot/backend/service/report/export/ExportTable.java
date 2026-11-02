package com.loadpilot.backend.service.report.export;

import java.util.List;

/** Tableau d'export (feuille Excel ou fichier CSV) : en-tetes avec unite et valeurs typees. */
public record ExportTable(String sheetName, String fileSuffix, List<Column> columns, List<List<Object>> rows) {

    public enum Kind { TEXT, INTEGER, DECIMAL, DATETIME, MIXED }

    /** decimals : nombre de decimales affichees (DECIMAL, et nombres d'une colonne MIXED). */
    public record Column(String header, Kind kind, int decimals) {
        public static Column text(String header) { return new Column(header, Kind.TEXT, 0); }
        public static Column integer(String header) { return new Column(header, Kind.INTEGER, 0); }
        public static Column decimal(String header, int decimals) { return new Column(header, Kind.DECIMAL, decimals); }
        public static Column dateTime(String header) { return new Column(header, Kind.DATETIME, 0); }
        public static Column mixed(String header, int decimals) { return new Column(header, Kind.MIXED, decimals); }
    }
}
