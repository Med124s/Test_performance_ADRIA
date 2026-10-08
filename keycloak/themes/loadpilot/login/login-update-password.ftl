<#import "template.ftl" as layout>
<#import "lp.ftl" as lp>
<#import "password-validation.ftl" as validator>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('password','password-confirm'); section>
    <#if section = "header">
        ${msg("updatePasswordTitle")}
    <#elseif section = "subtitle">
        <p class="lp-subtitle">${msg("lpUpdatePasswordSubtitle")}</p>
    <#elseif section = "form">
        <form id="kc-passwd-update-form" class="lp-form" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post" novalidate="novalidate">
            <@lp.password name="password-new" label=msg("passwordNew") fieldName="password" autocomplete="new-password" autofocus=true />
            <@lp.password name="password-confirm" label=msg("passwordConfirm") autocomplete="new-password" />

            <@lp.checkbox name="logout-sessions" label=msg("logoutOtherSessions") />

            <div class="lp-actions">
                <#if isAppInitiatedAction??>
                    <button class="lp-btn lp-btn-secondary" id="kc-cancel" name="cancel-aia" value="true" type="submit" formnovalidate>${msg("doCancel")}</button>
                    <button class="lp-btn lp-btn-primary" id="kc-submit" name="login" type="submit">${msg("lpSavePassword")}</button>
                <#else>
                    <button class="lp-btn lp-btn-primary lp-btn-block" id="kc-submit" name="login" type="submit">${msg("lpSavePassword")}</button>
                </#if>
            </div>
        </form>

        <#-- Vérification de la politique de mot de passe pendant la saisie (script de keycloak.v2). -->
        <template id="errorTemplate"><ul class="lp-field-error lp-field-error-list" aria-live="polite"></ul></template>
        <template id="errorItemTemplate"><li></li></template>
        <@validator.script field="password-new"/>
    </#if>
</@layout.registrationLayout>
