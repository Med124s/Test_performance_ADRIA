package com.loadpilot.backend.service.report.export;

import com.loadpilot.backend.service.report.export.ExportTable.Column;
import com.loadpilot.backend.service.report.export.ExportTable.Kind;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Classeur Excel (.xlsx, format Office Open XML) ecrit directement, sans bibliotheque : une feuille
 * par tableau, en-tetes en gras sur fond colore et figes, filtre automatique, largeur de colonnes
 * adaptee au contenu, nombres stockes comme NOMBRES (avec un format d'affichage), dates comme
 * vraies dates Excel au format jj/mm/aaaa hh:mm:ss. Les textes sont des chaines (jamais des
 * formules) : aucune injection de formule possible.
 */
public final class XlsxWriter {

    private XlsxWriter() {
    }

    // Styles (index dans cellXfs de styles.xml)
    private static final int S_HEADER = 1;
    private static final int S_INTEGER = 2;
    private static final int S_DECIMAL_1 = 3;
    private static final int S_DECIMAL_2 = 4;
    private static final int S_DATE = 5;
    private static final int S_TEXT = 6;

    private static final LocalDateTime EXCEL_EPOCH = LocalDateTime.of(1899, 12, 30, 0, 0);

    public static byte[] write(List<ExportTable> tables, String title, ZoneId zone, Instant created) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", contentTypes(tables.size()));
            entry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>\
                    <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>\
                    <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>\
                    </Relationships>""");
            entry(zip, "docProps/core.xml", core(title, created));
            entry(zip, "docProps/app.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>LoadPilot</Application></Properties>""");
            entry(zip, "xl/workbook.xml", workbook(tables));
            entry(zip, "xl/_rels/workbook.xml.rels", workbookRels(tables.size()));
            entry(zip, "xl/styles.xml", STYLES);
            for (int i = 0; i < tables.size(); i++) {
                entry(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheet(tables.get(i), zone, i == 0));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Génération du fichier Excel impossible", e);
        }
        return out.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String contentTypes(int sheets) {
        StringBuilder x = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                <Default Extension="xml" ContentType="application/xml"/>\
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>\
                <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>\
                <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>\
                <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""");
        for (int i = 1; i <= sheets; i++) {
            x.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return x.append("</Types>").toString();
    }

    private static String core(String title, Instant created) {
        String date = DateTimeFormatter.ISO_INSTANT.format(created.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" \
                xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" \
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">\
                <dc:title>%s</dc:title><dc:creator>LoadPilot</dc:creator>\
                <dcterms:created xsi:type="dcterms:W3CDTF">%s</dcterms:created></cp:coreProperties>""".formatted(xml(title), date);
    }

    private static String workbook(List<ExportTable> tables) {
        StringBuilder x = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><bookViews><workbookView activeTab="0"/></bookViews><sheets>""");
        for (int i = 0; i < tables.size(); i++) {
            x.append("<sheet name=\"").append(xml(tables.get(i).sheetName())).append("\" sheetId=\"").append(i + 1)
                    .append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        x.append("</sheets><definedNames>");
        // Plage du filtre automatique de chaque feuille (attendue par Excel en plus de <autoFilter>).
        for (int i = 0; i < tables.size(); i++) {
            ExportTable t = tables.get(i);
            x.append("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"").append(i).append("\" hidden=\"1\">'")
                    .append(xml(t.sheetName().replace("'", "''"))).append("'!").append(absoluteRange(t)).append("</definedName>");
        }
        return x.append("</definedNames></workbook>").toString();
    }

    private static String workbookRels(int sheets) {
        StringBuilder x = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""");
        for (int i = 1; i <= sheets; i++) {
            x.append("<Relationship Id=\"rId").append(i)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(i).append(".xml\"/>");
        }
        x.append("<Relationship Id=\"rId").append(sheets + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        return x.append("</Relationships>").toString();
    }

    /** Formats : 164 = 0,0 avec separateur de milliers ; 165 = date jj/mm/aaaa hh:mm:ss. Police Calibri 11. */
    private static final String STYLES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">\
            <numFmts count="2"><numFmt numFmtId="164" formatCode="#,##0.0"/><numFmt numFmtId="165" formatCode="dd/mm/yyyy\\ hh:mm:ss"/></numFmts>\
            <fonts count="2"><font><sz val="11"/><name val="Calibri"/><family val="2"/></font>\
            <font><b/><sz val="11"/><color rgb="FF312E81"/><name val="Calibri"/><family val="2"/></font></fonts>\
            <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>\
            <fill><patternFill patternType="solid"><fgColor rgb="FFEEF2FF"/><bgColor indexed="64"/></patternFill></fill></fills>\
            <borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border>\
            <border><left/><right/><top/><bottom style="thin"><color rgb="FFC7D2FE"/></bottom><diagonal/></border></borders>\
            <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>\
            <cellXfs count="7">\
            <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>\
            <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment vertical="center"/></xf>\
            <xf numFmtId="3" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>\
            <xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>\
            <xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>\
            <xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment horizontal="left"/></xf>\
            <xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>\
            </cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""";

    private static String sheet(ExportTable t, ZoneId zone, boolean selected) {
        List<Column> cols = t.columns();
        StringBuilder x = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" \
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""");
        x.append("<dimension ref=\"").append(range(t)).append("\"/>");
        // Ligne d'en-tete figee : elle reste visible en faisant defiler.
        x.append("<sheetViews><sheetView workbookViewId=\"0\"").append(selected ? " tabSelected=\"1\"" : "")
                .append("><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
                .append("<selection pane=\"bottomLeft\" activeCell=\"A2\" sqref=\"A2\"/></sheetView></sheetViews>");
        x.append("<sheetFormatPr defaultRowHeight=\"15\"/><cols>");
        for (int c = 0; c < cols.size(); c++) {
            x.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"")
                    .append(width(t, c, zone)).append("\" customWidth=\"1\"/>");
        }
        x.append("</cols><sheetData>");
        x.append("<row r=\"1\" ht=\"20\" customHeight=\"1\">");
        for (int c = 0; c < cols.size(); c++) x.append(textCell(ref(c, 1), cols.get(c).header(), S_HEADER));
        x.append("</row>");
        for (int r = 0; r < t.rows().size(); r++) {
            int rowNumber = r + 2;
            x.append("<row r=\"").append(rowNumber).append("\">");
            List<Object> row = t.rows().get(r);
            for (int c = 0; c < cols.size(); c++) {
                Object v = c < row.size() ? row.get(c) : null;
                if (v == null || (v instanceof String s && s.isEmpty())) continue;
                x.append(cell(ref(c, rowNumber), v, cols.get(c), zone));
            }
            x.append("</row>");
        }
        x.append("</sheetData>");
        x.append("<autoFilter ref=\"").append(range(t)).append("\"/>");
        x.append("<pageMargins left=\"0.5\" right=\"0.5\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>");
        return x.append("</worksheet>").toString();
    }

    private static String cell(String ref, Object v, Column col, ZoneId zone) {
        if (v instanceof Instant instant) {
            return "<c r=\"" + ref + "\" s=\"" + S_DATE + "\"><v>" + excelDate(instant, zone) + "</v></c>";
        }
        if (v instanceof Number n) {
            int style = n instanceof Integer || n instanceof Long ? S_INTEGER
                    : col.kind() == Kind.INTEGER ? S_INTEGER : col.decimals() <= 1 ? S_DECIMAL_1 : S_DECIMAL_2;
            String value = n instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : n instanceof Double d ? plain(d) : n.toString();
            return "<c r=\"" + ref + "\" s=\"" + style + "\"><v>" + value + "</v></c>";
        }
        return textCell(ref, v.toString(), S_TEXT);
    }

    private static String textCell(String ref, String text, int style) {
        String clean = xml(text);
        String space = !text.equals(text.strip()) ? " xml:space=\"preserve\"" : "";
        return "<c r=\"" + ref + "\" s=\"" + style + "\" t=\"inlineStr\"><is><t" + space + ">" + clean + "</t></is></c>";
    }

    private static String plain(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "0";
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    /** Date Excel : nombre de jours (et fraction) depuis le 30/12/1899, a l'heure locale du fuseau choisi. */
    static String excelDate(Instant instant, ZoneId zone) {
        LocalDateTime local = LocalDateTime.ofInstant(instant, zone).withNano(0);
        double days = Duration.between(EXCEL_EPOCH, local).getSeconds() / 86400.0;
        return BigDecimal.valueOf(days).setScale(8, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** Largeur en caracteres : texte le plus long de la colonne (en-tete compris), entre 8 et 60. */
    private static int width(ExportTable t, int c, ZoneId zone) {
        int max = t.columns().get(c).header().length() + 4; // + fleche du filtre
        for (List<Object> row : t.rows()) {
            Object v = c < row.size() ? row.get(c) : null;
            if (v == null) continue;
            int len = v instanceof Instant ? 19 : v instanceof Number n ? CsvTableWriter.number(n, t.columns().get(c)).length() + 2 : v.toString().length();
            max = Math.max(max, len + 2);
        }
        return Math.max(8, Math.min(60, max));
    }

    private static String range(ExportTable t) {
        return "A1:" + ref(t.columns().size() - 1, Math.max(1, t.rows().size() + 1));
    }

    private static String absoluteRange(ExportTable t) {
        return "$A$1:$" + column(t.columns().size() - 1) + "$" + Math.max(1, t.rows().size() + 1);
    }

    private static String ref(int col, int row) {
        return column(col) + row;
    }

    static String column(int index) {
        StringBuilder s = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            s.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return s.toString();
    }

    /** Texte XML sur : caracteres speciaux echappes, caracteres de controle interdits retires. */
    static String xml(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            switch (cp) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> {
                    if (cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF) || (cp >= 0xE000 && cp <= 0xFFFD) || cp >= 0x10000) {
                        out.appendCodePoint(cp);
                    }
                }
            }
        });
        return out.toString();
    }
}
