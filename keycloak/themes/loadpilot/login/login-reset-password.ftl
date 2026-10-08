<#import "template.ftl" as layout>
<#import "lp.ftl" as lp>
<@layout.registrationLayout displayInfo=false displayMessage=!messagesPerField.existsError('username'); section>
    <#if section = "header">
        ${msg("emailForgotTitle")}
    <#elseif section = "subtitle">
        <p class="lp-subtitle">
            <#if realm.duplicateEmailsAllowed>${msg("emailInstructionUsername")}<#else>${msg("emailInstruction")}</#if>
        </p>
    <#elseif section = "form">
        <form id="kc-reset-password-form" class="lp-form" action="${url.loginAction}" method="post">
            <#assign usernameLabel><#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if></#assign>
            <@lp.input name="username" label=usernameLabel value=auth.attemptedUsername!'' autofocus=true autocomplete="username" />

            <button class="lp-btn lp-btn-primary lp-btn-block" id="kc-form-buttons" type="submit">${msg("lpSendResetLink")}</button>
            <a class="lp-back" href="${url.loginUrl}"><@lp.icon name="arrow-left" /> ${msg("backToLogin")}</a>
        </form>
    </#if>
</@layout.registrationLayout>
