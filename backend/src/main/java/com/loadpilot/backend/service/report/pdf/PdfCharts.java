package com.loadpilot.backend.service.report.pdf;

import com.lowagie.text.Image;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.util.List;
import java.util.function.DoubleFunction;

/**
 * Graphiques vectoriels du rapport PDF (courbes et barres), dessines directement dans le PDF :
 * nets a toutes les echelles, sans image ni bibliotheque de graphiques. Une valeur null est un
 * trou dans la courbe (ex. pause), jamais un zero invente.
 */
final class PdfCharts {

    private PdfCharts() {
    }

    enum Axis { LEFT, RIGHT }

    /** spanGaps : relier les points de part et d'autre d'un trou (mesures espacees, ex. CPU / RAM). */
    record Series(String name, Color color, List<? extends Number> values, Axis axis, boolean spanGaps) {
        Series(String name, Color color, List<? extends Number> values, Axis axis) {
            this(name, color, values, axis, false);
        }
    }

    private static final Color GRID = new Color(0xE5, 0xE7, 0xEB);
    private static final Color AXIS_TEXT = new Color(0x6B, 0x72, 0x80);
    private static final float LEFT = 42;
    private static final float RIGHT_PAD = 10;
    private static final float BOTTOM = 18;
    private static final float TOP = 18;

    /**
     * Courbes sur un axe du temps. xSeconds[i] : seconde (depuis le debut du test) du point i.
     * Les series de l'axe RIGHT ont leur propre echelle (graduations a droite).
     */
    static Image lines(PdfWriter writer, BaseFont font, float width, float height, List<Double> xSeconds, List<Series> series,
                       DoubleFunction<String> leftLabel, DoubleFunction<String> rightLabel) throws Exception {
        PdfTemplate t = writer.getDirectContent().createTemplate(width, height);
        boolean hasRight = series.stream().anyMatch(s -> s.axis() == Axis.RIGHT);
        float right = hasRight ? 40 : RIGHT_PAD;
        float plotW = width - LEFT - right;
        float plotH = height - BOTTOM - TOP;
        double leftMax = niceMax(series.stream().filter(s -> s.axis() == Axis.LEFT).flatMap(s -> s.values().stream()));
        double rightMax = niceMax(series.stream().filter(s -> s.axis() == Axis.RIGHT).flatMap(s -> s.values().stream()));
        double xMax = xSeconds.isEmpty() ? 1 : Math.max(1, xSeconds.get(xSeconds.size() - 1));

        grid(t, font, plotW, plotH, right, leftMax, hasRight ? rightMax : -1, leftLabel, rightLabel);
        xAxis(t, font, plotW, xMax);

        for (Series s : series) {
            double max = s.axis() == Axis.RIGHT ? rightMax : leftMax;
            t.setColorStroke(s.color());
            t.setLineWidth(1.4f);
            boolean drawing = false;
            List<float[]> points = new java.util.ArrayList<>();
            for (int i = 0; i < s.values().size() && i < xSeconds.size(); i++) {
                Number v = s.values().get(i);
                if (v == null) {
                    if (!s.spanGaps()) drawing = false;
                    continue;
                }
                float x = LEFT + (float) (xSeconds.get(i) / xMax * plotW);
                float y = BOTTOM + (float) (v.doubleValue() / max * plotH);
                if (!drawing) { t.moveTo(x, y); drawing = true; } else { t.lineTo(x, y); }
                points.add(new float[] {x, y});
            }
            t.stroke();
            if (s.spanGaps()) {
                // Mesures espacees : chaque mesure est marquee d'un point.
                t.setColorFill(s.color());
                for (float[] p : points) t.circle(p[0], p[1], 1.6f);
                t.fill();
            }
        }
        legend(t, font, series.stream().map(s -> new Object[] {s.name(), s.color()}).toList());
        return Image.getInstance(t);
    }

    /** Barres sur un axe du temps (ex. erreurs par intervalle). */
    static Image bars(PdfWriter writer, BaseFont font, float width, float height, List<Double> xSeconds, List<? extends Number> values,
                      String name, Color color, double bucketSeconds) throws Exception {
        PdfTemplate t = writer.getDirectContent().createTemplate(width, height);
        float plotW = width - LEFT - RIGHT_PAD;
        float plotH = height - BOTTOM - TOP;
        double max = niceMax(values.stream());
        double xMax = xSeconds.isEmpty() ? 1 : Math.max(1, xSeconds.get(xSeconds.size() - 1) + bucketSeconds);
        grid(t, font, plotW, plotH, RIGHT_PAD, max, -1, v -> PdfText.integer(Math.round(v)), null);
        xAxis(t, font, plotW, xMax);
        float barW = (float) Math.max(1, bucketSeconds / xMax * plotW * 0.8);
        t.setColorFill(color);
        for (int i = 0; i < values.size() && i < xSeconds.size(); i++) {
            Number v = values.get(i);
            if (v == null || v.doubleValue() <= 0) continue;
            float x = LEFT + (float) (xSeconds.get(i) / xMax * plotW);
            float h = (float) (v.doubleValue() / max * plotH);
            t.rectangle(x, BOTTOM, barW, Math.max(0.8f, h));
        }
        t.fill();
        legend(t, font, List.<Object[]>of(new Object[] {name, color}));
        return Image.getInstance(t);
    }

    private static void grid(PdfTemplate t, BaseFont font, float plotW, float plotH, float right, double leftMax, double rightMax,
                             DoubleFunction<String> leftLabel, DoubleFunction<String> rightLabel) {
        int steps = 4;
        for (int i = 0; i <= steps; i++) {
            float y = BOTTOM + plotH * i / steps;
            t.setColorStroke(GRID);
            t.setLineWidth(0.5f);
            t.moveTo(LEFT, y);
            t.lineTo(LEFT + plotW, y);
            t.stroke();
            text(t, font, PdfText.safe(leftLabel.apply(leftMax * i / steps)), LEFT - 4, y - 2.5f, PdfContentByte.ALIGN_RIGHT);
            if (rightMax > 0 && rightLabel != null) {
                text(t, font, PdfText.safe(rightLabel.apply(rightMax * i / steps)), LEFT + plotW + 4, y - 2.5f, PdfContentByte.ALIGN_LEFT);
            }
        }
    }

    private static void xAxis(PdfTemplate t, BaseFont font, float plotW, double xMax) {
        for (int i = 0; i <= 4; i++) {
            float x = LEFT + plotW * i / 4;
            int align = i == 0 ? PdfContentByte.ALIGN_LEFT : i == 4 ? PdfContentByte.ALIGN_RIGHT : PdfContentByte.ALIGN_CENTER;
            text(t, font, PdfText.axisSeconds(xMax * i / 4), x, BOTTOM - 11, align);
        }
    }

    private static void legend(PdfTemplate t, BaseFont font, List<Object[]> items) {
        float x = LEFT;
        float y = t.getHeight() - 11;
        for (Object[] item : items) {
            t.setColorFill((Color) item[1]);
            t.rectangle(x, y, 8, 3);
            t.fill();
            String label = PdfText.safe((String) item[0]);
            text(t, font, label, x + 11, y - 2, PdfContentByte.ALIGN_LEFT);
            x += 18 + font.getWidthPoint(label, 7.5f);
        }
    }

    private static void text(PdfTemplate t, BaseFont font, String text, float x, float y, int align) {
        t.beginText();
        t.setFontAndSize(font, 7.5f);
        t.setColorFill(AXIS_TEXT);
        t.showTextAligned(align, text, x, y, 0);
        t.endText();
    }

    /** Maximum « rond » de l'axe (1, 2, 2,5, 5 ou 10 fois une puissance de 10), au moins 1. */
    static double niceMax(java.util.stream.Stream<? extends Number> values) {
        double max = values.filter(java.util.Objects::nonNull).mapToDouble(Number::doubleValue).max().orElse(0);
        if (max <= 0) return 1;
        double magnitude = Math.pow(10, Math.floor(Math.log10(max)));
        for (double step : new double[] {1, 2, 2.5, 5, 10}) {
            if (step * magnitude >= max) return step * magnitude;
        }
        return 10 * magnitude;
    }
}
