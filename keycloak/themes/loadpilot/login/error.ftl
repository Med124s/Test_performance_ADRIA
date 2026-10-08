<#import "template.ftl" as layout>
<#import "lp.ftl" as lp>
<@layout.registrationLayout displayMessage=false; section>
    <#if section = "header">
        ${kcSanitize(msg("errorTitle"))?no_esc}
    <#elseif section = "form">
        <div id="kc-error-message" class="lp-state">
            <@lp.alert type="error">${kcSanitize(message.summary)?no_esc}</@lp.alert>
            <#if traceId??>
                <p class="lp-muted" id="traceId">${msg("traceIdSupportMessage", traceId)}</p>
            </#if>
            <#if !skipLink??>
                <#if client?? && client.baseUrl?has_content>
                    <a id="backToApplication" class="lp-btn lp-btn-primary lp-btn-block" href="${client.baseUrl}">${msg("backToApplication")}</a>
                </#if>
            </#if>
        </div>
    </#if>
</@layout.registrationLayout>
