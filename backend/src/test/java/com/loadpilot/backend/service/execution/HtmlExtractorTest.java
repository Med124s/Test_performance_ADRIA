package com.loadpilot.backend.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HtmlExtractorTest {

    private static final String LOGIN_PAGE = """
            <html><body>
              <form action="/login" method="post">
                <input type="hidden" name="csrf_token" value="a1b2c3d4">
                <input type="text" name="username">
                <input type="hidden" name="empty_field" value="">
              </form>
              <div id="compte" data-account-id="ACC001">Compte courant</div>
              <ul><li class="op">Virement</li><li class="op">Facture</li><li class="op">Dépôt</li></ul>
              <span class="vide"> </span>
            </body></html>""";

    @Test
    void hiddenFieldValue_extracted_withoutExplanation() {
        HtmlExtractor.Match match = HtmlExtractor.find(LOGIN_PAGE, "input[name=csrf_token]::attr(value)");
        assertThat(match.value()).isEqualTo("a1b2c3d4");
        assertThat(match.reason()).isNull();
        assertThat(match.count()).isEqualTo(1);
        assertThat(match.elementName()).isEqualTo("csrf_token");
    }

    @Test
    void dataAttribute_andElementText_extracted() {
        assertThat(HtmlExtractor.extract(LOGIN_PAGE, "#compte::attr(data-account-id)")).isEqualTo("ACC001");
        assertThat(HtmlExtractor.extract(LOGIN_PAGE, "div#compte")).isEqualTo("Compte courant");
        assertThat(HtmlExtractor.extract(LOGIN_PAGE, "  input[name=csrf_token] ::attr( value )  ")).isEqualTo("a1b2c3d4");
    }

    @Test
    void absentElement_returnsNull_withClearExplanation() {
        HtmlExtractor.Match match = HtmlExtractor.find(LOGIN_PAGE, "input[name=xsrf]::attr(value)");
        assertThat(match.value()).isNull();
        assertThat(match.count()).isZero();
        assertThat(match.reason()).isEqualTo("aucun élément ne correspond à « input[name=xsrf] » dans la réponse");
    }

    @Test
    void absentAttribute_returnsNull_withClearExplanation() {
        HtmlExtractor.Match match = HtmlExtractor.find(LOGIN_PAGE, "input[name=username]::attr(value)");
        assertThat(match.value()).isNull();
        assertThat(match.reason()).isEqualTo("l'élément « input name=username » n'a pas d'attribut « value »");
    }

    @Test
    void emptyAttribute_orElementWithoutText_isExplained() {
        assertThat(HtmlExtractor.find(LOGIN_PAGE, "input[name=empty_field]::attr(value)").reason())
                .isEqualTo("attribut « value » vide sur l'élément « input name=empty_field »");
        assertThat(HtmlExtractor.find(LOGIN_PAGE, "span.vide").reason()).isEqualTo("l'élément « span » ne contient aucun texte");
    }

    @Test
    void ambiguousSelector_theFirstElementOfThePageIsKept_andTheCountIsReported() {
        HtmlExtractor.Match match = HtmlExtractor.find(LOGIN_PAGE, "li.op");
        assertThat(match.value()).isEqualTo("Virement");
        assertThat(match.count()).isEqualTo(3);
        assertThat(match.reason()).isNull();
        assertThat(HtmlExtractor.extract(LOGIN_PAGE, "input[type=hidden]::attr(value)")).isEqualTo("a1b2c3d4");
    }

    @Test
    void malformedHtml_isRepairedLikeABrowser() {
        String broken = "<html><body><form><input type=hidden name=csrf_token value=zz99><div><p>texte non fermé";
        assertThat(HtmlExtractor.extract(broken, "input[name=csrf_token]::attr(value)")).isEqualTo("zz99");
        assertThat(HtmlExtractor.extract(broken, "p")).isEqualTo("texte non fermé");
    }

    @Test
    void invalidSelector_orMissingAttributeName_neverThrows() {
        assertThat(HtmlExtractor.find(LOGIN_PAGE, "input[name=").reason()).isEqualTo("sélecteur CSS invalide « input[name= »");
        assertThat(HtmlExtractor.find(LOGIN_PAGE, "input::attr()").reason()).isEqualTo("indiquez le nom de l'attribut (ex. value)");
        assertThat(HtmlExtractor.find(LOGIN_PAGE, "::attr(value)").reason()).isEqualTo("indiquez un sélecteur CSS (ex. input[name=csrf_token])");
        assertThat(HtmlExtractor.specProblem("input[name=csrf_token]::attr(value)")).isNull();
    }

    @Test
    void emptyOrNonHtmlResponse_isExplained() {
        assertThat(HtmlExtractor.find("", "input").reason()).isEqualTo("réponse vide : aucune page HTML à lire");
        assertThat(HtmlExtractor.find("{\"token\":\"abc\"}", "input[name=token]::attr(value)").reason())
                .isEqualTo("aucun élément ne correspond à « input[name=token] » dans la réponse");
        assertThat(HtmlExtractor.find(LOGIN_PAGE, null).value()).isNull();
    }

    @Test
    void sensitiveHtmlCaptures_areRecognisedBySelectorOrElementName() {
        assertThat(TestRunMasking.isSensitiveHtmlCapture("input[name=csrf_token]::attr(value)", "csrf_token")).isTrue();
        assertThat(TestRunMasking.isSensitiveHtmlCapture("form input[type=hidden]::attr(value)", "authenticity_token")).isTrue();
        assertThat(TestRunMasking.isSensitiveHtmlCapture("input[name=_xsrf]::attr(value)", "_xsrf")).isTrue();
        assertThat(TestRunMasking.isSensitiveHtmlCapture("#compte::attr(data-account-id)", "compte")).isFalse();
    }
}
