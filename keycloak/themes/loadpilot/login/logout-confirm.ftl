<#import "template.ftl" as layout>
<#import "lp.ftl" as lp>
<@layout.registrationLayout; section>
    <#if section = "header">
        ${msg("logoutConfirmTitle")}
    <#elseif section = "subtitle">
        <p class="lp-subtitle">${msg("logoutConfirmHeader")}</p>
    <#elseif section = "form">
        <div id="kc-logout-confirm" class="lp-state">
            <form action="${url.logoutConfirmAction}" onsubmit="confirmLogout.disabled = true; return true;" method="POST">
                <input type="hidden" name="session_code" value="${logoutConfirm.code}">
                <button class="lp-btn lp-btn-primary lp-btn-block" name="confirmLogout" id="kc-logout" type="submit"><@lp.icon name="logout" /> ${msg("doLogout")}</button>
            </form>
            <#if !logoutConfirm.skipLink && (client.baseUrl)?has_content>
                <a class="lp-back" href="${client.baseUrl}"><@lp.icon name="arrow-left" /> ${msg("backToApplication")}</a>
            </#if>
        </div>
    </#if>
</@layout.registrationLayout>
