package com.loadpilot.backend.service.execution;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.jsoup.select.QueryParser;
import org.jsoup.select.Selector;

/** Extraction d'une valeur dans un corps HTML avec un selecteur CSS (jsoup), ex. "input[name=csrf_token]::attr(value)". */
public final class HtmlExtractor {

    /** Taille maximale analysee. */
    static final int MAX_CHARS = 5 * 1024 * 1024;

    private static final Pattern ATTRIBUTE_SUFFIX = Pattern.compile("^(.*?)\\s*::attr\\(\\s*([^)]*?)\\s*\\)\\s*$", Pattern.DOTALL);

    private HtmlExtractor() {
    }

    /** Valeur (ou null), raison de l'absence, nombre d'elements trouves et nom de l'element retenu. */
    record Match(String value, String reason, int count, String elementName) {
        static Match failure(String reason, int count) {
            return new Match(null, reason, count, null);
        }
    }

    /** Selecteur et attribut lus dans le chemin ("selecteur::attr(nom)" ; attribut null = texte). */
    record Spec(String selector, String attribute) {
    }

    static Spec parseSpec(String spec) {
        String trimmed = spec == null ? "" : spec.trim();
        Matcher m = ATTRIBUTE_SUFFIX.matcher(trimmed);
        return m.matches() ? new Spec(m.group(1).trim(), m.group(2)) : new Spec(trimmed, null);
    }

    /** Probleme de syntaxe du chemin (selecteur invalide, attribut vide), ou null. */
    public static String specProblem(String spec) {
        Spec parsed = parseSpec(spec);
        if (parsed.selector().isEmpty()) return "indiquez un sélecteur CSS (ex. input[name=csrf_token])";
        if (parsed.attribute() != null && parsed.attribute().isEmpty()) return "indiquez le nom de l'attribut (ex. value)";
        try {
            QueryParser.parse(parsed.selector());
            return null;
        } catch (Selector.SelectorParseException | IllegalArgumentException e) {
            return "sélecteur CSS invalide « " + parsed.selector() + " »";
        }
    }

    static String extract(String html, String spec) {
        return find(html, spec).value();
    }

    static Match find(String html, String spec) {
        if (spec == null || spec.isBlank()) return new Match(null, null, 0, null);
        String problem = specProblem(spec);
        if (problem != null) return Match.failure(problem, 0);
        if (html == null || html.isBlank()) return Match.failure("réponse vide : aucune page HTML à lire", 0);
        Spec parsed = parseSpec(spec);
        try {
            Document document = Jsoup.parse(html.length() > MAX_CHARS ? html.substring(0, MAX_CHARS) : html);
            Elements elements = document.select(parsed.selector());
            if (elements.isEmpty()) {
                return Match.failure("aucun élément ne correspond à « " + parsed.selector() + " » dans la réponse", 0);
            }
            Element first = elements.first();
            String name = first.hasAttr("name") ? first.attr("name") : first.hasAttr("id") ? first.id() : null;
            String value;
            if (parsed.attribute() != null) {
                if (!first.hasAttr(parsed.attribute())) {
                    return new Match(null, "l'élément « " + describe(first) + " » n'a pas d'attribut « " + parsed.attribute() + " »",
                            elements.size(), name);
                }
                value = first.attr(parsed.attribute()).trim();
                if (value.isEmpty()) {
                    return new Match(null, "attribut « " + parsed.attribute() + " » vide sur l'élément « " + describe(first) + " »",
                            elements.size(), name);
                }
            } else {
                value = first.text().trim();
                if (value.isEmpty()) {
                    return new Match(null, "l'élément « " + describe(first) + " » ne contient aucun texte", elements.size(), name);
                }
            }
            return new Match(value, null, elements.size(), name);
        } catch (RuntimeException e) {
            return Match.failure("page HTML illisible", 0);
        }
    }

    /** « input name=csrf_token » : balise et attribut name/id, jamais la valeur. */
    private static String describe(Element element) {
        if (element.hasAttr("name")) return element.tagName() + " name=" + element.attr("name");
        if (!element.id().isEmpty()) return element.tagName() + " id=" + element.id();
        return element.tagName();
    }
}
