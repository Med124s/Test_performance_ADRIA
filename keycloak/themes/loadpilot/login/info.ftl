<#import "template.ftl" as layout>
<#import "lp.ftl" as lp>
<@layout.registrationLayout displayMessage=false; section>
    <#if section = "header">
        <#if messageHeader??>
            ${kcSanitize(msg("${messageHeader}"))?no_esc}
        <#else>
            ${message.summary}
        </#if>
    <#elseif section = "form">
        <div id="kc-info-message" class="lp-state">
            <#-- Sans en-tête dédié, le message sert déjà de titre : il n'est pas répété dans un encadré. -->
            <#if messageHeader?? || requiredActions??>
                <@lp.alert type=(message.type)!"info">
                    ${message.summary}<#if requiredActions??><#list requiredActions>&nbsp;: <b><#items as reqActionItem>${kcSanitize(msg("requiredAction.${reqActionItem}"))?no_esc}<#sep>, </#items></b></#list></#if>
                </@lp.alert>
            </#if>
            <#assign hasLink = false>
            <#if !skipLink??>
                <#if pageRedirectUri?has_content>
                    <#assign hasLink = true>
                    <a class="lp-btn lp-btn-primary lp-btn-block" href="${pageRedirectUri}">${msg("backToApplication")}</a>
                <#elseif actionUri?has_content>
                    <#assign hasLink = true>
                    <a class="lp-btn lp-btn-primary lp-btn-block" href="${actionUri}">${msg("proceedWithAction")}</a>
                <#elseif (client.baseUrl)?has_content>
                    <#assign hasLink = true>
                    <a class="lp-btn lp-btn-primary lp-btn-block" href="${client.baseUrl}">${msg("backToApplication")}</a>
                </#if>
            </#if>
            <#if !hasLink>
                <p class="lp-muted">${msg("lpInfoNothingElse")}</p>
            </#if>
        </div>
    </#if>
</@layout.registrationLayout>
