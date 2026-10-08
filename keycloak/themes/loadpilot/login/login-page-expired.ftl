<#import "template.ftl" as layout>
<#import "lp.ftl" as lp>
<@layout.registrationLayout displayMessage=false; section>
    <#if section = "header">
        ${msg("pageExpiredTitle")}
    <#elseif section = "form">
        <div class="lp-state">
            <@lp.alert type="warning">${msg("lpPageExpiredText")}</@lp.alert>
            <a id="loginRestartLink" class="lp-btn lp-btn-primary lp-btn-block" href="${url.loginRestartFlowUrl}">${msg("lpRestartLogin")}</a>
            <a id="loginContinueLink" class="lp-btn lp-btn-secondary lp-btn-block" href="${url.loginAction}">${msg("lpContinueLogin")}</a>
        </div>
    </#if>
</@layout.registrationLayout>
