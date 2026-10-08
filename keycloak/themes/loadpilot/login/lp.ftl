<#-- Composants LoadPilot réutilisés par les pages du thème (icônes Bootstrap Icons en SVG, comme dans l'application). -->

<#macro icon name>
<#switch name>
<#case "speedometer"><svg class="lp-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" focusable="false"><path d="M8 4a.5.5 0 0 1 .5.5V6a.5.5 0 0 1-1 0V4.5A.5.5 0 0 1 8 4M3.732 5.732a.5.5 0 0 1 .707 0l.915.914a.5.5 0 1 1-.708.708l-.914-.915a.5.5 0 0 1 0-.707M2 10a.5.5 0 0 1 .5-.5h1.586a.5.5 0 0 1 0 1H2.5A.5.5 0 0 1 2 10m9.5 0a.5.5 0 0 1 .5-.5h1.5a.5.5 0 0 1 0 1H12a.5.5 0 0 1-.5-.5m.754-4.246a.39.39 0 0 0-.527-.02L7.547 9.31a.91.91 0 1 0 1.302 1.258l3.434-4.297a.39.39 0 0 0-.029-.518z"/><path fill-rule="evenodd" d="M0 10a8 8 0 1 1 15.547 2.661c-.442 1.253-1.845 1.602-2.932 1.25C11.309 13.488 9.475 13 8 13c-1.474 0-3.310.488-4.615.911-1.087.352-2.49.003-2.932-1.25A8 8 0 0 1 0 10m8-7a7 7 0 0 0-6.603 9.329c.203.575.923.876 1.680.63C4.397 12.533 6.358 12 8 12s3.604.532 4.923.96c.757.245 1.477-.056 1.680-.631A7 7 0 0 0 8 3"/></svg><#break>
<#case "diagram"><svg class="lp-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" focusable="false"><path fill-rule="evenodd" d="M6 3.5A1.5 1.5 0 0 1 7.5 2h1A1.5 1.5 0 0 1 10 3.5v1A1.5 1.5 0 0 1 8.5 6v1H14a.5.5 0 0 1 .5.5v1a.5.5 0 0 1-1 0V8h-5v.5a.5.5 0 0 1-1 0V8h-5v.5a.5.5 0 0 1-1 0v-1A.5.5 0 0 1 2 7h5.5V6A1.5 1.5 0 0 1 6 4.5zM8.5 5a.5.5 0 0 0 .5-.5v-1a.5.5 0 0 0-.5-.5h-1a.5.5 0 0 0-.5.5v1a.5.5 0 0 0 .5.5zM0 11.5A1.5 1.5 0 0 1 1.5 10h1A1.5 1.5 0 0 1 4 11.5v1A1.5 1.5 0 0 1 2.5 14h-1A1.5 1.5 0 0 1 0 12.5zm1.5-.5a.5.5 0 0 0-.5.5v1a.5.5 0 0 0 .5.5h1a.5.5 0 0 0 .5-.5v-1a.5.5 0 0 0-.5-.5zm4.5.5A1.5 1.5 0 0 1 7.5 10h1a1.5 1.5 0 0 1 1.5 1.5v1A1.5 1.5 0 0 1 8.5 14h-1A1.5 1.5 0 0 1 6 12.5zm1.5-.5a.5.5 0 0 0-.5.5v1a.5.5 0 0 0 .5.5h1a.5.5 0 0 0 .5-.5v-1a.5.5 0 0 0-.5-.5zm4.5.5a1.5 1.5 0 0 1 1.5-1.5h1a1.5 1.5 0 0 1 1.5 1.5v1a1.5 1.5 0 0 1-1.5 1.5h-1a1.5 1.5 0 0 1-1.5-1.5zm1.5-.5a.5.5 0 0 0-.5.5v1a.5.5 0 0 0 .5.5h1a.5.5 0 0 0 .5-.5v-1a.5.5 0 0 0-.5-.5z"/></svg><#break>
<#case "activity"><svg class="lp-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" focusable="false"><path fill-rule="evenodd" d="M6 2a.5.5 0 0 1 .47.33L10 12.036l1.53-4.208A.5.5 0 0 1 12 7.5h3.5a.5.5 0 0 1 0 1h-3.15l-1.88 5.17a.5.5 0 0 1-.94 0L6 3.964 4.470 8.171A.5.5 0 0 1 4 8.5H.5a.5.5 0 0 1 0-1h3.15l1.88-5.17A.5.5 0 0 1 6 2"/></svg><#break>
<#case "chart"><svg class="lp-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" focusable="false"><path d="M11 2a1 1 0 0 1 1-1h2a1 1 0 0 1 1 1v12h.5a.5.5 0 0 1 0 1H.5a.5.5 0 0 1 0-1H1v-3a1 1 0 0 1 1-1h2a1 1 0 0 1 1 1v3h1V7a1 1 0 0 1 1-1h2a1 1 0 0 1 1 1v7h1zm1 12h2V2h-2zm-3 0V7H7v7zm-5 0v-3H2v3z"/></svg><#break>
<#case "eye"><svg class="lp-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" focusable="false"><path d="M16 8s-3-5.5-8-5.5S0 8 0 8s3 5.5 8 5.5S16 8 16 8M1.173 8a13 13 0 0 1 1.660-2.043C4.120 4.668 5.880 3.5 8 3.5s3.879 1.168 5.168 2.457A13 13 0 0 1 14.828 8q-.086.13-.195.288c-.335.48-.83 1.12-1.465 1.755C11.879 11.332 10.119 12.5 8 12.5s-3.879-1.168-5.168-2.457A13 13 0 0 1 1.172 8z"/><path d="M8 5.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5M4.5 8a3.5 3.5 0 1 1 7 0 3.5 3.5 0 0 1-7 0"/></svg><#break>
<#case "eye-slash"><svg class="lp-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" focusable="false"><path d="M13.359 11.238C15.060 9.720 16 8 16 8s-3-5.5-8-5.5a7 7 0 0 0-2.790.588l.770.771A6 6 0 0 1 8 3.5c2.120 0 3.879 1.168 5.168 2.457A13 13 0 0 1 14.828 8q-.086.13-.195.288c-.335.48-.83 1.12-1.465 1.755q-.247.248-.517.486z"/><path d="M11.297 9.176a3.5 3.5 0 0 0-4.474-4.474l.823.823a2.5 2.5 0 0 1 2.829 2.829zm-2.943 1.299.822.822a3.5 3.5 0 0 1-4.474-4.474l.823.823a2.5 2.5 0 0 0 2.829 2.829"/><path d="M3.350 5.470q-.27.24-.518.487A13 13 0 0 0 1.172 8l.195.288c.335.48.83 1.12 1.465 1.755C4.121 11.332 5.881 12.5 8 12.5c.716 0 1.390-.133 2.020-.36l.770.772A7 7 0 0 1 8 13.5C3 13.5 0 8 0 8s.939-1.721 2.641-3.238l.708.709zm10.296 8.884-12-12 .708-.708 12 12z"/></svg><#break>
<#case "shield"><svg class="lp-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" focusable="false"><path d="M5.338 1.590a61 61 0 0 0-2.837.856.48.48 0 0 0-.328.39c-.554 4.157.726 7.190 2.253 9.188a10.7 10.7 0 0 0 2.287 2.233c.346.244.652.42.893.533q.18.085.293.118a1 1 0 0 0 .101.025 1 1 0 0 0 .1-.025q.114-.034.294-.118c.24-.113.547-.29.893-.533a10.7 10.7 0 0 0 2.287-2.233c1.527-1.997 2.807-5.031 2.253-9.188a.48.48 0 0 0-.328-.39c-.651-.213-1.750-.56-2.837-.855C9.552 1.290 8.531 1.067 8 1.067c-.53 0-1.552.223-2.662.524zM5.072.560C6.157.265 7.310 0 8 0s1.843.265 2.928.560c1.110.3 2.229.655 2.887.870a1.540 1.540 0 0 1 1.044 1.262c.596 4.477-.787 7.795-2.465 9.990a11.8 11.8 0 0 1-2.517 2.453 7 7 0 0 1-1.048.625c-.28.132-.581.24-.829.24s-.548-.108-.829-.24a7 7 0 0 1-1.048-.625 11.8 11.8 0 0 1-2.517-2.453C1.928 10.487.545 7.169 1.141 2.692A1.540 1.540 0 0 1 2.185 1.430 63 63 0 0 1 5.072.560"/><path d="M9.5 6.5a1.5 1.5 0 0 1-1 1.415l.385 1.990a.5.5 0 0 1-.491.595h-.788a.5.5 0 0 1-.49-.595l.384-1.990a1.5 1.5 0 1 1 2-1.415"/></svg><#break>
<#case "error"><svg class="lp-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" focusable="false"><path d="M8 15A7 7 0 1 1 8 1a7 7 0 0 1 0 14m0 1A8 8 0 1 0 8 0a8 8 0 0 0 0 16"/><path d="M7.002 11a1 1 0 1 1 2 0 1 1 0 0 1-2 0M7.1 4.995a.905.905 0 1 1 1.8 0l-.35 3.507a.552.552 0 0 1-1.1 0z"/></svg><#break>
<#case "warning"><svg class="lp-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" focusable="false"><path d="M7.938 2.016A.13.13 0 0 1 8.002 2a.13.13 0 0 1 .063.016.15.15 0 0 1 .054.057l6.857 11.667c.036.06.035.124.002.183a.2.2 0 0 1-.054.06.1.1 0 0 1-.066.017H1.146a.1.1 0 0 1-.066-.017.2.2 0 0 1-.054-.06.18.18 0 0 1 .002-.183L7.884 2.073a.15.15 0 0 1 .054-.057m1.044-.45a1.13 1.13 0 0 0-1.960 0L.165 13.233c-.457.778.091 1.767.98 1.767h13.713c.889 0 1.438-.99.98-1.767z"/><path d="M7.002 12a1 1 0 1 1 2 0 1 1 0 0 1-2 0M7.1 5.995a.905.905 0 1 1 1.8 0l-.35 3.507a.552.552 0 0 1-1.1 0z"/></svg><#break>
<#case "success"><svg class="lp-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" focusable="false"><path d="M8 15A7 7 0 1 1 8 1a7 7 0 0 1 0 14m0 1A8 8 0 1 0 8 0a8 8 0 0 0 0 16"/><path d="m10.97 4.97-.02.022-3.473 4.425-2.093-2.094a.75.75 0 0 0-1.060 1.060L6.970 11.030a.75.75 0 0 0 1.079-.02l3.992-4.990a.75.75 0 0 0-1.071-1.050"/></svg><#break>
<#case "arrow-left"><svg class="lp-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" focusable="false"><path fill-rule="evenodd" d="M15 8a.5.5 0 0 0-.5-.5H2.707l3.147-3.146a.5.5 0 1 0-.708-.708l-4 4a.5.5 0 0 0 0 .708l4 4a.5.5 0 0 0 .708-.708L2.707 8.5H14.5A.5.5 0 0 0 15 8"/></svg><#break>
<#case "logout"><svg class="lp-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" focusable="false"><path fill-rule="evenodd" d="M10 12.5a.5.5 0 0 1-.5.5h-8a.5.5 0 0 1-.5-.5v-9a.5.5 0 0 1 .5-.5h8a.5.5 0 0 1 .5.5v2a.5.5 0 0 0 1 0v-2A1.5 1.5 0 0 0 9.5 2h-8A1.5 1.5 0 0 0 0 3.5v9A1.5 1.5 0 0 0 1.5 14h8a1.5 1.5 0 0 0 1.5-1.5v-2a.5.5 0 0 0-1 0z"/><path fill-rule="evenodd" d="M15.854 8.354a.5.5 0 0 0 0-.708l-3-3a.5.5 0 0 0-.708.708L14.293 7.5H5.5a.5.5 0 0 0 0 1h8.793l-2.147 2.146a.5.5 0 0 0 .708.708z"/></svg><#break>
<#default><svg class="lp-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" focusable="false"><path d="M8 15A7 7 0 1 1 8 1a7 7 0 0 1 0 14m0 1A8 8 0 1 0 8 0a8 8 0 0 0 0 16"/><path d="m8.93 6.588-2.29.287-.082.38.45.083c.294.07.352.176.288.469l-.738 3.468c-.194.897.105 1.319.808 1.319.545 0 1.178-.252 1.465-.598l.088-.416c-.2.176-.492.246-.686.246-.275 0-.375-.193-.304-.533zM9 4.5a1 1 0 1 1-2 0 1 1 0 0 1 2 0"/></svg>
</#switch>
</#macro>

<#macro logo compact=false>
<div class="lp-logo<#if compact> lp-logo--compact</#if>">
    <span class="lp-logo-mark"><@icon name="speedometer" /></span>
    <span class="lp-logo-text">
        <span class="lp-logo-name">LoadPilot</span>
        <span class="lp-logo-sub">${msg("lpSubtitle")}</span>
    </span>
</div>
</#macro>

<#-- type : error | warning | success | info (types de message Keycloak). -->
<#macro alert type="info">
<div class="lp-alert lp-alert--${type}" role="${(type == 'error')?then('alert', 'status')}">
    <@icon name=type />
    <span class="lp-alert-text kc-feedback-text"><#nested></span>
</div>
</#macro>

<#macro fieldError name error>
    <#-- Le conteneur sert aussi au contrôle de mot de passe en direct (password-validation.ftl). -->
    <div id="input-error-container-${name}">
        <#if error?has_content>
            <p class="lp-field-error" id="input-error-${name}" aria-live="polite">${kcSanitize(error)?no_esc}</p>
        </#if>
    </div>
</#macro>

<#macro input name label value="" type="text" autocomplete="off" fieldName=name error=messagesPerField.get(fieldName) autofocus=false required=false invalid=false>
<#local hasError = error?has_content || invalid>
<div class="lp-field">
    <label class="lp-label" for="${name}">${label}<#if required> <span class="lp-required" aria-hidden="true">*</span></#if></label>
    <input class="lp-input<#if hasError> lp-input--error</#if>" id="${name}" name="${name}" type="${type}" value="${value}"
           autocomplete="${autocomplete}" <#if autofocus>autofocus</#if> <#if required>required</#if>
           <#if hasError>aria-invalid="true"</#if> <#if error?has_content>aria-describedby="input-error-${name}"</#if> />
    <@fieldError name=name error=error />
</div>
</#macro>

<#macro password name label autocomplete="current-password" fieldName=name error=messagesPerField.get(fieldName) autofocus=false required=false invalid=false>
<#local hasError = error?has_content || invalid>
<div class="lp-field">
    <label class="lp-label" for="${name}">${label}<#if required> <span class="lp-required" aria-hidden="true">*</span></#if></label>
    <div class="lp-input-wrap">
        <input class="lp-input lp-input--password<#if hasError> lp-input--error</#if>" id="${name}" name="${name}" type="password"
               autocomplete="${autocomplete}" <#if autofocus>autofocus</#if> <#if required>required</#if>
               <#if hasError>aria-invalid="true"</#if> <#if error?has_content>aria-describedby="input-error-${name}"</#if> />
        <button type="button" class="lp-reveal" data-lp-reveal aria-controls="${name}" aria-pressed="false"
                aria-label="${msg('showPassword')}" data-label-show="${msg('showPassword')}" data-label-hide="${msg('hidePassword')}">
            <span class="lp-reveal-show"><@icon name="eye" /></span>
            <span class="lp-reveal-hide" hidden><@icon name="eye-slash" /></span>
        </button>
    </div>
    <@fieldError name=name error=error />
</div>
</#macro>

<#macro checkbox name label checked=false>
<label class="lp-check" for="${name}">
    <input type="checkbox" id="${name}" name="${name}" <#if checked>checked</#if> />
    <span>${label}</span>
</label>
</#macro>

<#macro secure>
<p class="lp-secure"><@icon name="shield" /> <span>${msg("lpSecureConnection")}</span></p>
</#macro>
