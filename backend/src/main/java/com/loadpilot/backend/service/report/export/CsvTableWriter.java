package com.loadpilot.backend.service.report.export;

import com.loadpilot.backend.service.report.export.ExportTable.Column;
import com.loadpilot.backend.service.report.export.ExportTable.Kind;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Un tableau en CSV « Excel en francais » : UTF-8 avec BOM (accents reconnus), separateur « ; »,
 * virgule decimale, dates jj/mm/aaaa hh:mm:ss a l'heure locale, une ligne d'en-tete en francais
 * avec unites, fins de ligne CRLF. Guillemets autour de toute valeur contenant « ; », un guillemet,
 * un retour a la ligne ou des espaces en bordure (guillemet interne double). Protection contre
 * l'injection de formule : un TEXTE commencant par = + - @ (ou tabulation) est prefixe d'une
 * apostrophe ; les nombres negatifs restent des nombres.
 */
public final class CsvTableWriter {

    private CsvTableWriter() {
    }

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.FRANCE);

    public static byte[] write(ExportTable table, ZoneId zone) {
        StringBuilder csv = new StringBuilder();
        List<Column> cols = table.columns();
        for (int c = 0; c < cols.size(); c++) {
            if (c > 0) csv.append(';');
            csv.append(text(cols.get(c).header()));
        }
        csv.append("\r\n");
        for (List<Object> row : table.rows()) {
            for (int c = 0; c < cols.size(); c++) {
                if (c > 0) csv.append(';');
                csv.append(value(c < row.size() ? row.get(c) : null, cols.get(c), zone));
            }
            csv.append("\r\n");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(BOM);
        out.writeBytes(csv.toString().getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    static String value(Object v, Column col, ZoneId zone) {
        if (v == null) return "";
        if (v instanceof Instant instant) return DATE_TIME.format(instant.atZone(zone));
        if (v instanceof Number n) return number(n, col);
        return text(v.toString());
    }

    /** Entier tel quel ; decimal avec virgule et le nombre de decimales de la colonne (sans separateur de milliers). */
    static String number(Number n, Column col) {
        if (n instanceof Integer || n instanceof Long || col.kind() == Kind.INTEGER) {
            return BigDecimal.valueOf(n.doubleValue()).setScale(0, RoundingMode.HALF_UP).toPlainString();
        }
        BigDecimal b = n instanceof BigDecimal big ? big : BigDecimal.valueOf(n.doubleValue());
        return b.setScale(col.decimals(), RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    static String text(String raw) {
        String s = raw;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        boolean quote = s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r") || !s.equals(s.strip());
        return quote ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
