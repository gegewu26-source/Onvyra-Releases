package com.constx.onvyra.ui.pages.netdisk

import com.constx.onvyra.data.netdisk.NetDiskPlatform
import java.net.URI

/** Opens only the provider's public login entry; it never fills or submits an authentication form. */
internal object NetDiskOfficialLoginPage {
    fun preparationScript(platform: NetDiskPlatform, url: String?, accountMode: Boolean): String? {
        if (accountMode) return null
        val uri = runCatching { URI(url.orEmpty()) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) return null
        return when {
            platform == NetDiskPlatform.XUNLEI && uri.host == "pan.xunlei.com" &&
                uri.path.trimEnd('/') == "/login" -> xunlei
            platform == NetDiskPlatform.GUANGYA && uri.host == "www.guangyapan.com" &&
                uri.path in listOf("", "/") && uri.fragment.orEmpty() in listOf("", "/") -> guangya
            else -> null
        }
    }

    // The current official /login route embeds its SSO iframe inside #login-wrap.
    // Keep the desktop UA (the mobile site redirects away), but make that one form fit the phone.
    private val xunlei = """
        (function() { try {
          if (location.protocol !== 'https:' || location.hostname !== 'pan.xunlei.com' ||
              location.pathname.replace(/\/${'$'}/, '') !== '/login') return 'ignored';
          var viewport = document.querySelector('meta[name="viewport"]');
          if (viewport) viewport.setAttribute('content', 'width=device-width,initial-scale=1');
          if (!document.getElementById('onvyra-official-login-layout')) {
            var style = document.createElement('style');
            style.id = 'onvyra-official-login-layout';
            style.textContent = '.login-page{min-width:0!important;width:100%!important;}' +
              '.login-page .web-header,.login-page .web-footer,.login-page .banner-wrap{display:none!important;}' +
              '.login-page .pan-share-web,.login-page .pan-share-web-content{min-width:0!important;width:100%!important;min-height:0!important;height:auto!important;display:flex!important;justify-content:center!important;padding:12px 0!important;}' +
              '.login-page .login-web{margin:0!important;width:100%!important;max-width:400px!important;min-width:0!important;}' +
              '#login-wrap{max-width:100vw!important;}#login-wrap iframe{max-width:100%!important;}';
            (document.head || document.documentElement).appendChild(style);
          }
          return document.querySelector('#login-wrap iframe') ? 'ready' : 'waiting';
        } catch (_) { return 'waiting'; } })()
    """.trimIndent()

    // www.guangyapan.com is a product landing page. Its observed “立即登录” button opens
    // the site's own SMS/QR dialog. /#/oauth/login is NOT used: it belongs to third-party authorization.
    private val guangya = """
        (function() { try {
          if (location.protocol !== 'https:' || location.hostname !== 'www.guangyapan.com' ||
              (location.pathname !== '' && location.pathname !== '/') ||
              (location.hash !== '' && location.hash !== '#' && location.hash !== '#/')) return 'ignored';
          if (!document.getElementById('onvyra-official-login-layout')) {
            var style = document.createElement('style');
            style.id = 'onvyra-official-login-layout';
            style.textContent = '@media(max-width:600px){._14tg7470{width:calc(100vw - 24px)!important;max-width:400px!important;}.rqrhb00{width:100%!important;max-width:100%!important;}.rqrhb01{display:none!important;}.rqrhb00>:last-child{width:100%!important;min-width:0!important;box-sizing:border-box!important;}}';
            (document.head || document.documentElement).appendChild(style);
          }
          function visible(element) {
            if (!element || !element.getClientRects().length) return false;
            var style = window.getComputedStyle(element);
            return style.display !== 'none' && style.visibility !== 'hidden' && Number(style.opacity) > 0.01;
          }
          var fields = document.querySelectorAll('input[type="tel"],input[type="password"],input[autocomplete="one-time-code"],input[placeholder*="手机"],input[placeholder*="验证码"]');
          for (var f = 0; f < fields.length; f++) if (visible(fields[f])) return 'ready';
          if (window.__onvyraOfficialLoginEntryOpened) return 'opened';
          var buttons = document.querySelectorAll('button,a,[role="button"]');
          for (var i = 0; i < buttons.length; i++) {
            var button = buttons[i];
            if (!visible(button) || button.disabled || button.closest('form,[role="dialog"]')) continue;
            if ((button.textContent || '').replace(/\s/g, '') !== '立即登录') continue;
            window.__onvyraOfficialLoginEntryOpened = true;
            button.click();
            return 'opened';
          }
          return 'waiting';
        } catch (_) { return 'waiting'; } })()
    """.trimIndent()
}
