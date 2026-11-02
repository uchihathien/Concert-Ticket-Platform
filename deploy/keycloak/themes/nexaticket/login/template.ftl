<#--
  Vỏ chung cho mọi trang đăng nhập của NexaTicket.

  Dựng lại từ base/login/template.ftl của Keycloak 26.0, giữ nguyên các khối nested ("header",
  "form", "socialProviders", "info", "show-username") để mọi template con của Keycloak vẫn chạy.
  Chỉ đổi phần bao ngoài:

  - Bỏ `#kc-header` (tên realm in to bằng chữ, lạc tông) — logo nằm trong thẻ thay vào đó.
  - Thẻ form ở giữa; từ 900px trở lên có thêm cột giới thiệu bên trái, giống `SignInScreen` của
    web. Dưới 900px (điện thoại, popup 480px của web-customer) chỉ còn thẻ form.
  - Nội dung cột giới thiệu đổi theo client đang đăng nhập: khách mua vé, ban tổ chức, superadmin
    hay nhân viên soát vé không cần đọc cùng một lời chào.
-->
<#import "footer.ftl" as loginFooter>
<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false>
<#--
  Client đang đăng nhập. Ở trang đầu của link "Quên mật khẩu?" mở thẳng (login-actions/
  reset-credentials) Keycloak KHÔNG đưa `client` vào template, và render bằng theme của realm thay
  vì `login_theme` của client. Địa chỉ form vẫn mang `client_id`, nên lấy từ đó khi thiếu.
-->
<#assign ntClientId = (client.clientId)!''>
<#if ntClientId == "" && ((url.loginAction)!'')?contains("client_id=")>
    <#assign ntClientId = url.loginAction?keep_after("client_id=")?keep_before("&")>
</#if>
<#--
  Các client gán `login_theme=nexaticket-lime` — PHẢI khớp LIME_CLIENTS trong
  deploy/scripts/keycloak-set-theme.sh. Chỉ dùng cho trường hợp ở trên; bình thường Keycloak tự
  chọn theme theo client.
-->
<#assign ntLimeClients = ["mobile-customer", "mobile-staff", "web-admin"]>
<#if ntClientId?starts_with("web-admin")>
    <#assign ntAudience = "organizer">
<#elseif ntClientId?starts_with("web-platform")>
    <#assign ntAudience = "platform">
<#elseif ntClientId?starts_with("web-scanner") || ntClientId == "mobile-staff">
    <#assign ntAudience = "staff">
<#else>
    <#assign ntAudience = "customer">
</#if>
<!DOCTYPE html>
<html class="${properties.kcHtmlClass!} nt-html"<#if realm.internationalizationEnabled> lang="${locale.currentLanguageTag}" dir="${(locale.rtl)?then('rtl','ltr')}"</#if>>

<head>
    <meta charset="utf-8">
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta name="robots" content="noindex, nofollow">
    <#-- Trình duyệt dựng thanh cuộn, ô chọn và nền khi tải trang theo màu tối ngay từ đầu. -->
    <meta name="color-scheme" content="dark">

    <#if properties.meta?has_content>
        <#list properties.meta?split(' ') as meta>
            <meta name="${meta?split('==')[0]}" content="${meta?split('==')[1]}"/>
        </#list>
    </#if>
    <title>${msg("loginTitle",(realm.displayName!''))}</title>
    <link rel="icon" type="image/png" href="${url.resourcesPath}/img/icon.png" />
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
    <#-- Trang đang render bằng theme đỏ cho một client xanh chanh (xem đầu file): nạp thêm bộ biến xanh chanh. -->
    <#if ntLimeClients?seq_contains(ntClientId) && !(properties.styles!'')?contains("nexaticket-lime.css")>
        <link href="${url.resourcesPath?keep_before_last('/')}/nexaticket-lime/css/nexaticket-lime.css" rel="stylesheet" />
    </#if>
    <#if properties.scripts?has_content>
        <#list properties.scripts?split(' ') as script>
            <script src="${url.resourcesPath}/${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <script type="importmap">
        {
            "imports": {
                "rfc4648": "${url.resourcesCommonPath}/vendor/rfc4648/rfc4648.js"
            }
        }
    </script>
    <script src="${url.resourcesPath}/js/menu-button-links.js" type="module"></script>
    <#if scripts??>
        <#list scripts as script>
            <script src="${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <script type="module">
        import { startSessionPolling } from "${url.resourcesPath}/js/authChecker.js";

        startSessionPolling(
          "${url.ssoLoginInOtherTabsUrl?no_esc}"
        );
    </script>
</head>

<body class="${properties.kcBodyClass!} nt-body ${bodyClass}">
<div class="nt-screen">
    <#-- Cột giới thiệu: chỉ hiện từ 900px. Ba dòng lợi ích, không phải một đoạn văn. -->
    <aside class="nt-panel">
        <div class="nt-panel-inner">
            <p class="nt-panel-eyebrow">NEXATICKET</p>
            <p class="nt-panel-title">${msg("ntPanelTitle_" + ntAudience)}</p>
            <ul class="nt-panel-list">
                <li>${msg("ntPanelItem1_" + ntAudience)}</li>
                <li>${msg("ntPanelItem2_" + ntAudience)}</li>
                <li>${msg("ntPanelItem3_" + ntAudience)}</li>
            </ul>
        </div>
    </aside>

    <main class="nt-pane">
    <div class="nt-card ${properties.kcFormCardClass!}">
        <div class="nt-card-top">
            <span class="nt-brand"><img src="${url.resourcesPath}/img/logo-dark.png" alt="NexaTicket" width="128" height="40"></span>
            <#if realm.internationalizationEnabled  && locale.supported?size gt 1>
                <div class="${properties.kcLocaleMainClass!} nt-locale" id="kc-locale">
                    <div id="kc-locale-wrapper" class="${properties.kcLocaleWrapperClass!}">
                        <div id="kc-locale-dropdown" class="menu-button-links ${properties.kcLocaleDropDownClass!}">
                            <button tabindex="1" id="kc-current-locale-link" aria-label="${msg("languages")}" aria-haspopup="true" aria-expanded="false" aria-controls="language-switch1">${locale.current}</button>
                            <ul role="menu" tabindex="-1" aria-labelledby="kc-current-locale-link" aria-activedescendant="" id="language-switch1" class="${properties.kcLocaleListClass!}">
                                <#assign i = 1>
                                <#list locale.supported as l>
                                    <li class="${properties.kcLocaleListItemClass!}" role="none">
                                        <a role="menuitem" id="language-${i}" class="${properties.kcLocaleItemClass!}" href="${l.url}">${l.label}</a>
                                    </li>
                                    <#assign i++>
                                </#list>
                            </ul>
                        </div>
                    </div>
                </div>
            </#if>
        </div>

        <header class="${properties.kcFormHeaderClass!} nt-header">
        <#if !(auth?has_content && auth.showUsername() && !auth.showResetCredentials())>
            <#if displayRequiredFields>
                <h1 id="kc-page-title"><#nested "header"></h1>
                <p class="nt-required-note"><span class="required">*</span> ${msg("requiredFields")}</p>
            <#else>
                <h1 id="kc-page-title"><#nested "header"></h1>
            </#if>
        <#else>
            <#nested "show-username">
            <#-- Đang ở bước thứ hai (mật khẩu, OTP…): nhắc người dùng đang đăng nhập tài khoản nào. -->
            <div id="kc-username" class="${properties.kcFormGroupClass!}">
                <label id="kc-attempted-username">${auth.attemptedUsername}</label>
                <a id="reset-login" href="${url.loginRestartFlowUrl}" aria-label="${msg("restartLoginTooltip")}">
                    <div class="kc-login-tooltip">
                        <i class="${properties.kcResetFlowIcon!}"></i>
                        <span class="kc-tooltip-text">${msg("restartLoginTooltip")}</span>
                    </div>
                </a>
            </div>
            <#if displayRequiredFields>
                <p class="nt-required-note"><span class="required">*</span> ${msg("requiredFields")}</p>
            </#if>
        </#if>
        </header>

        <div id="kc-content">
        <div id="kc-content-wrapper">

          <#-- App-initiated actions should not see warning messages about the need to complete the action -->
          <#-- during login.                                                                               -->
          <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
              <div class="alert-${message.type} ${properties.kcAlertClass!} pf-m-<#if message.type = 'error'>danger<#else>${message.type}</#if>" role="<#if message.type = 'error'>alert<#else>status</#if>">
                  <div class="pf-c-alert__icon">
                      <#if message.type = 'success'><span class="${properties.kcFeedbackSuccessIcon!}"></span></#if>
                      <#if message.type = 'warning'><span class="${properties.kcFeedbackWarningIcon!}"></span></#if>
                      <#if message.type = 'error'><span class="${properties.kcFeedbackErrorIcon!}"></span></#if>
                      <#if message.type = 'info'><span class="${properties.kcFeedbackInfoIcon!}"></span></#if>
                  </div>
                      <span class="${properties.kcAlertTitleClass!}">${kcSanitize(message.summary)?no_esc}</span>
              </div>
          </#if>

          <#nested "form">

          <#if auth?has_content && auth.showTryAnotherWayLink()>
              <form id="kc-select-try-another-way-form" action="${url.loginAction}" method="post">
                  <div class="${properties.kcFormGroupClass!}">
                      <input type="hidden" name="tryAnotherWay" value="on"/>
                      <a href="#" id="try-another-way"
                         onclick="document.forms['kc-select-try-another-way-form'].requestSubmit();return false;">${msg("doTryAnotherWay")}</a>
                  </div>
              </form>
          </#if>

          <#nested "socialProviders">

          <#if displayInfo>
              <div id="kc-info" class="${properties.kcSignUpClass!}">
                  <div id="kc-info-wrapper" class="${properties.kcInfoAreaWrapperClass!}">
                      <#nested "info">
                  </div>
              </div>
          </#if>
        </div>
        </div>

        <@loginFooter.content/>
    </div>

    <#-- Cùng ý với dòng chú thích trên trang /login của web: người dùng biết mình đang gõ mật khẩu ở đâu. -->
    <p class="nt-trust">${msg("ntTrustNote")}</p>
    </main>
</div>
</body>
</html>
</#macro>
