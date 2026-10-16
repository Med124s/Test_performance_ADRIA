package com.loadpilot.backend.service.report.pdf;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Textes et nombres du rapport PDF, en francais. Les polices standard du PDF (Helvetica) couvrent
 * le jeu Windows-1252 : accents, guillemets francais, apostrophe typographique, tirets... Les rares
 * caracteres hors de ce jeu (espace fine insecable, fleches, inegalites) sont remplaces par un
 * equivalent lisible, jamais par un carre vide.
 */
public final class PdfText {

    private PdfText() {
    }

    private static final String NBSP = "\u00A0";

    /** Texte sur pour la police du PDF. */
    public static String safe(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            switch (cp) {
                case 0x202F, 0x2009, 0x2007 -> out.append(NBSP);
                case 0x2264 -> out.append("<=");
                case 0x2265 -> out.append(">=");
                case 0x2192 -> out.append("->");
                case 0x2190 -> out.append("<-");
                case 0x2191 -> out.append("+");
                case 0x2193 -> out.append("-");
                case 0x2212 -> out.append('-');
                case 0x2713, 0x2714 -> out.append("OK");
                case 0x2717, 0x2718 -> out.append("X");
                default -> {
                    if (cp < 0x20 && cp != '\n') out.append(' ');
                    else if (cp <= 0xFF || "€‚ƒ„…†‡ˆ‰Š‹ŒŽ‘’“”•–—˜™š›œžŸ".indexOf(cp) >= 0) {
                        out.appendCodePoint(cp);
                    } else {
                        out.append('?');
                    }
                }
            }
        });
        return out.toString();
    }

    /** Entier avec espace insecable tous les trois chiffres (« 12 345 »). */
    public static String integer(long value) {
        String digits = Long.toString(Math.abs(value));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) out.append(NBSP);
            out.append(digits.charAt(i));
        }
        return (value < 0 ? "-" : "") + out;
    }

    /** Nombre decimal a la francaise (« 36,7 »), sans decimale inutile (« 12 » et non « 12,0 »). */
    public static String decimal(double value, int decimals) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "—";
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(decimals, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        String plain = rounded.toPlainString();
        String intPart = plain.contains(".") ? plain.substring(0, plain.indexOf('.')) : plain;
        String frac = plain.contains(".") ? plain.substring(plain.indexOf('.') + 1) : "";
        boolean negative = intPart.startsWith("-");
        String grouped = integer(Long.parseLong(negative ? intPart.substring(1) : intPart));
        return (negative ? "-" : "") + grouped + (frac.isEmpty() ? "" : "," + frac);
    }

    public static String ms(Long value) {
        return value == null ? "—" : integer(value) + NBSP + "ms";
    }

    public static String percent(Double value) {
        return value == null ? "—" : decimal(value, 2) + NBSP + "%";
    }

    public static String rps(Double value) {
        return value == null ? "—" : decimal(value, 1) + NBSP + "req/s";
    }

    /** Duree lisible : « 45 s », « 2 min 05 s », « 1 h 03 min ». */
    public static String duration(Long millis) {
        if (millis == null) return "—";
        long s = Math.round(millis / 1000.0);
        if (s < 60) return s + NBSP + "s";
        if (s < 3600) return (s / 60) + NBSP + "min" + (s % 60 == 0 ? "" : " " + String.format(Locale.ROOT, "%02d", s % 60) + NBSP + "s");
        return (s / 3600) + NBSP + "h" + " " + String.format(Locale.ROOT, "%02d", (s % 3600) / 60) + NBSP + "min";
    }

    /** Graduation d'axe en secondes depuis le debut du test. */
    public static String axisSeconds(double seconds) {
        long s = Math.round(seconds);
        if (s < 120) return s + NBSP + "s";
        return (s / 60) + NBSP + "min" + (s % 60 == 0 ? "" : " " + (s % 60));
    }

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm", Locale.FRANCE);

    public static String dateTime(Instant instant, ZoneId zone) {
        return instant == null ? "—" : DATE_TIME.format(instant.atZone(zone));
    }
}
