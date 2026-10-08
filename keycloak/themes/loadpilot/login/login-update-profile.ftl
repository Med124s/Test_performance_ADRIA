<#import "template.ftl" as layout>
<#import "user-profile-commons.ftl" as userProfileCommons>
<#-- Action « Mettre à jour le profil » (UPDATE_PROFILE) : champs générés par le profil utilisateur de Keycloak. -->
<@layout.registrationLayout displayMessage=messagesPerField.exists('global') displayRequiredFields=true; section>
    <#if section = "header">
        ${msg("loginProfileTitle")}
    <#elseif section = "subtitle">
        <p class="lp-subtitle">${msg("lpUpdateProfileSubtitle")}</p>
    <#elseif section = "form">
        <form id="kc-update-profile-form" class="lp-form lp-form--profile" action="${url.loginAction}" method="post">
            <@userProfileCommons.userProfileFormFields/>
            <div class="lp-actions">
                <#if isAppInitiatedAction??>
                    <button class="lp-btn lp-btn-secondary" type="submit" name="cancel-aia" value="true" formnovalidate>${msg("doCancel")}</button>
                    <button class="lp-btn lp-btn-primary" type="submit">${msg("lpSaveProfile")}</button>
                <#else>
                    <button class="lp-btn lp-btn-primary lp-btn-block" type="submit">${msg("lpSaveProfile")}</button>
                </#if>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
