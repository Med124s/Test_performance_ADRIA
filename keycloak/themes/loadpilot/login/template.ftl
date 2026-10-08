<#-- Mise en page commune LoadPilot : panneau de marque à gauche, carte de formulaire à droite.
     Même API que keycloak.v2 (macro registrationLayout + sections header/form/info/socialProviders),
     donc les pages héritées de keycloak.v2 s'affichent aussi dans ce cadre. -->
<#import "footer.ftl" as loginFooter>
<#import "lp.ftl" as lp>

<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false>
<!DOCTYPE html>
<html class="${properties.kcHtmlClass!} lp-html" lang="${lang}"<#if realm.internationalizationEnabled> dir="${(locale.rtl)?then('rtl','ltr')}"</#if>>
<head>
    <meta charset="utf-8">
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta name="color-scheme" content="light dark">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="robots" content="noindex, nofollow">
    <title>${msg("lpPageTitle")}</title>
    <link rel="icon" href="${url.resourcesPath}/img/favicon.svg" type="image/svg+xml" />
    <#if properties.stylesCommon?has_content>
        <#list properties.stylesCommon?split(' ') as style>
            <link href="${url.resourcesCommonPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
    <#if properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
            <link href="${url.resourcesPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
    <script type="importmap">
        {
            "imports": {
                "rfc4648": "${url.resourcesCommonPath}/vendor/rfc4648/rfc4648.js"
            }
        }
    </script>
    <#-- Mode sombre suivant le système (les pages PatternFly héritées utilisent cette classe). -->
    <script type="module" async blocking="render">
        <#outputformat "JavaScript">
        const DARK_MODE_CLASS = ${properties.kcDarkModeClass?c};
        const mediaQuery = window.matchMedia("(prefers-color-scheme: dark)");
        const updateDarkMode = (on) => document.documentElement.classList.toggle(DARK_MODE_CLASS, on);
        updateDarkMode(mediaQuery.matches);
        mediaQuery.addEventListener("change", (event) => updateDarkMode(event.matches));
        </#outputformat>
    </script>
    <#if properties.scripts?has_content>
        <#list properties.scripts?split(' ') as script>
            <script src="${url.resourcesPath}/${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <#if scripts??>
        <#list scripts as script>
            <script src="${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <script type="module" src="${url.resourcesPath}/js/passwordVisibility.js"></script>
    <script type="module" src="${url.resourcesPath}/js/loadpilot.js"></script>
    <script type="module">
        <#outputformat "JavaScript">
        import { startSessionPolling } from ${(url.resourcesPath + "/js/authChecker.js")?c};
        startSessionPolling(${url.ssoLoginInOtherTabsUrl?c});
        </#outputformat>
    </script>
    <script type="module">
        document.addEventListener("click", (event) => {
            const link = event.target.closest("a[data-once-link]");
            if (!link) return;
            if (link.getAttribute("aria-disabled") === "true") { event.preventDefault(); return; }
            const { disabledClass } = link.dataset;
            if (disabledClass) link.classList.add(...disabledClass.trim().split(/\s+/));
            link.setAttribute("role", "link");
            link.setAttribute("aria-disabled", "true");
        });
    </script>
    <#if authenticationSession??>
        <script type="module">
            <#outputformat "JavaScript">
            import { checkAuthSession } from ${(url.resourcesPath + "/js/authChecker.js")?c};
            checkAuthSession(${authenticationSession.authSessionIdHash?c});
            </#outputformat>
        </script>
    </#if>
</head>

<body id="keycloak-bg" class="lp-body ${bodyClass}" data-page-id="login-${pageId}">
<div class="lp-page">
    <section class="lp-brand" aria-label="LoadPilot">
        <div class="lp-brand-inner">
            <@lp.logo />
            <p class="lp-tagline">${msg("lpTagline")}</p>
            <ul class="lp-features">
                <li>
                    <span class="lp-feature-icon"><@lp.icon name="diagram" /></span>
                    <span><strong>${msg("lpFeatureScenariosTitle")}</strong>${msg("lpFeatureScenariosText")}</span>
                </li>
                <li>
                    <span class="lp-feature-icon"><@lp.icon name="activity" /></span>
                    <span><strong>${msg("lpFeatureLiveTitle")}</strong>${msg("lpFeatureLiveText")}</span>
                </li>
                <li>
                    <span class="lp-feature-icon"><@lp.icon name="chart" /></span>
                    <span><strong>${msg("lpFeatureReportsTitle")}</strong>${msg("lpFeatureReportsText")}</span>
                </li>
            </ul>
        </div>
        <#-- Graphique décoratif (temps de réponse fictifs), purement visuel. -->
        <svg class="lp-chart" viewBox="0 0 600 220" preserveAspectRatio="none" aria-hidden="true" focusable="false">
            <defs>
                <linearGradient id="lp-chart-fill" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stop-color="#fff" stop-opacity="0.28" />
                    <stop offset="100%" stop-color="#fff" stop-opacity="0" />
                </linearGradient>
            </defs>
            <g class="lp-chart-grid">
                <line x1="0" y1="55" x2="600" y2="55" /><line x1="0" y1="110" x2="600" y2="110" /><line x1="0" y1="165" x2="600" y2="165" />
            </g>
            <path class="lp-chart-area" d="M0 170 C40 160 60 120 100 128 S170 160 210 132 S280 70 320 92 S390 140 430 104 S500 52 540 66 S585 88 600 74 L600 220 L0 220 Z" />
            <path class="lp-chart-line" d="M0 170 C40 160 60 120 100 128 S170 160 210 132 S280 70 320 92 S390 140 430 104 S500 52 540 66 S585 88 600 74" />
            <path class="lp-chart-p95" d="M0 140 L600 140" />
            <circle class="lp-chart-dot" cx="540" cy="66" r="5" />
        </svg>
    </section>

    <main class="lp-panel">
        <#if realm.internationalizationEnabled && locale.supported?size gt 1>
            <div class="lp-locale">
                <label class="lp-sr-only" for="login-select-toggle">${msg("languages")}</label>
                <select id="login-select-toggle" class="lp-locale-select" onchange="if (this.value) window.location.href=this.value">
                    <#list locale.supported?sort_by("label") as l>
                        <option value="${l.url}" ${(l.languageTag == locale.currentLanguageTag)?then('selected','')}>${l.label}</option>
                    </#list>
                </select>
            </div>
        </#if>

        <div class="lp-card">
            <div class="lp-mobile-logo"><@lp.logo compact=true /></div>

            <header class="lp-card-header">
                <h1 class="lp-title" id="kc-page-title"><#nested "header"></h1>
                <#nested "subtitle">
            </header>

            <#if auth?has_content && auth.showUsername() && !auth.showResetCredentials()>
                <div class="lp-attempted">
                    <#nested "show-username">
                    <span class="lp-attempted-name" id="kc-attempted-username">${auth.attemptedUsername}</span>
                    <a id="reset-login" class="lp-link lp-attempted-change" href="${url.loginRestartFlowUrl}" aria-label="${msg('restartLoginTooltip')}">${msg("lpChangeUser")}</a>
                </div>
            </#if>

            <#if displayRequiredFields>
                <p class="lp-required-hint"><span class="lp-required" aria-hidden="true">*</span> ${msg("requiredFields")}</p>
            </#if>

            <#-- Les avertissements « action requise » ne s'affichent pas pendant une action lancée par l'application. -->
            <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
                <@lp.alert type=message.type>${kcSanitize(message.summary)?no_esc}</@lp.alert>
            </#if>

            <#nested "form">

            <#if auth?has_content && auth.showTryAnotherWayLink()>
                <form id="kc-select-try-another-way-form" action="${url.loginAction}" method="post" novalidate="novalidate">
                    <input type="hidden" name="tryAnotherWay" value="on"/>
                    <button type="submit" class="lp-btn lp-btn-secondary lp-btn-block lp-mt">${msg("doTryAnotherWay")}</button>
                </form>
            </#if>

            <#if switchOrganizationEnabled?? && switchOrganizationEnabled>
                <form id="kc-switch-organization-form" action="${url.loginAction}" method="post" novalidate="novalidate">
                    <input type="hidden" name="switchOrganization" value="true"/>
                    <button type="submit" class="lp-btn lp-btn-secondary lp-btn-block lp-mt">${msg("doSwitchOrganization")}</button>
                </form>
            </#if>

            <#nested "socialProviders">

            <#if displayInfo>
                <div id="kc-info" class="lp-info">
                    <div id="kc-info-wrapper"><#nested "info"></div>
                </div>
            </#if>

            <@loginFooter.content/>
        </div>

        <p class="lp-legal">© LoadPilot · ${msg("lpSubtitle")}</p>
    </main>
</div>
</body>
</html>
</#macro>
