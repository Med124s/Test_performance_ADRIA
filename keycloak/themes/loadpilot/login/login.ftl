<#import "template.ftl" as layout>
<#import "lp.ftl" as lp>
<#import "social-providers.ftl" as identityProviders>
<#import "passkeys.ftl" as passkeys>
<#-- Erreur d'identifiants : affichée une seule fois en haut de la carte, les deux champs passent en rouge. -->
<#assign credentialsError = messagesPerField.existsError('username','password')>
<@layout.registrationLayout displayMessage=true displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled??; section>
    <#if section = "header">
        ${msg("loginAccountTitle")}
    <#elseif section = "subtitle">
        <p class="lp-subtitle">${msg("lpLoginSubtitle")}</p>
    <#elseif section = "form">
        <div id="kc-form">
            <#if realm.password>
                <form id="kc-form-login" class="lp-form" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post" novalidate="novalidate">
                    <#if !usernameHidden??>
                        <#assign usernameLabel><#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if></#assign>
                        <@lp.input name="username" label=usernameLabel value=login.username!'' error="" invalid=credentialsError
                            autofocus=true autocomplete="${(enableWebAuthnConditionalUI?has_content)?then('username webauthn', 'username')}" />
                    </#if>

                    <@lp.password name="password" label=msg("password") error="" invalid=credentialsError autofocus=usernameHidden?? autocomplete="current-password" />

                    <#if (realm.rememberMe && !usernameHidden??) || realm.resetPasswordAllowed>
                        <div class="lp-form-row">
                            <#if realm.rememberMe && !usernameHidden??>
                                <@lp.checkbox name="rememberMe" label=msg("rememberMe") checked=login.rememberMe?? />
                            <#else>
                                <span></span>
                            </#if>
                            <#if realm.resetPasswordAllowed>
                                <a class="lp-link" href="${url.loginResetCredentialsUrl}">${msg("doForgotPassword")}</a>
                            </#if>
                        </div>
                    </#if>

                    <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
                    <button class="lp-btn lp-btn-primary lp-btn-block" name="login" id="kc-login" type="submit">${msg("doLogIn")}</button>
                </form>
                <#-- Texte fixe, affiché en permanence : il ne dit jamais si un compte précis est bloqué. -->
                <p class="lp-hint" id="kc-lockout-hint">${msg("lpLockoutHint")}</p>
            </#if>
        </div>
        <@passkeys.conditionalUIData />
        <@lp.secure />
    <#elseif section = "socialProviders" >
        <#if realm.password && social.providers?? && social.providers?has_content>
            <@identityProviders.show social=social/>
        </#if>
    <#elseif section = "info" >
        <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
            <div id="kc-registration">
                <span>${msg("noAccount")} <a class="lp-link" href="${url.registrationUrl}">${msg("doRegister")}</a></span>
            </div>
        </#if>
    </#if>
</@layout.registrationLayout>
