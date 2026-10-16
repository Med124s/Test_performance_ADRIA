package com.loadpilot.backend.service.report.export;

import java.util.List;

/**
 * Un tableau d'export (une feuille Excel, ou un fichier CSV) : en-tetes en francais avec unite,
 * et des valeurs TYPEES (texte, entier, decimal, date) - jamais des nombres deja mis en texte :
 * chaque format (XLSX, CSV) les ecrit a sa maniere (nombre Excel, ou virgule decimale en CSV).
 *
 * Valeurs acceptees : null (cellule vide), String, Integer / Long, Double / BigDecimal, java.time.Instant.
 */
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
