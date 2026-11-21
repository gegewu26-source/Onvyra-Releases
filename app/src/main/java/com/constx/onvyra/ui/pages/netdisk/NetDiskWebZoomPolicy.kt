package com.constx.onvyra.ui.pages.netdisk

import com.constx.onvyra.data.netdisk.NetDiskPlatform

/** Override only the providers observed to disable page zoom; preserve their layout and login flow. */
internal object NetDiskWebZoomPolicy {
    fun origins(platform: NetDiskPlatform): Set<String> = when (platform) {
        NetDiskPlatform.UC -> setOf("https://uc.cn", "https://*.uc.cn", "https://ucweb.com", "https://*.ucweb.com")
        NetDiskPlatform.BAIDU -> setOf("https://baidu.com", "https://*.baidu.com")
        NetDiskPlatform.PAN115 -> setOf("https://115.com", "https://*.115.com")
        else -> emptySet()
    }

    // Runs in the existing document, including trusted authentication frames. Nothing is read from
    // inputs, cookies or storage. Retain width/initial-scale/viewport-fit and every unrelated token.
    val DOCUMENT_SCRIPT = """
        (function() { try {
          if (window.__onvyraWebZoomPolicy) { window.__onvyraWebZoomPolicy.refresh(); return; }
          function refresh() {
            if (!document.head) return;
            var all = document.querySelectorAll('meta'), viewports = [];
            for (var i = 0; i < all.length; i++) {
              if ((all[i].getAttribute('name') || '').toLowerCase() === 'viewport') viewports.push(all[i]);
            }
            if (!viewports.length) {
              var meta = document.createElement('meta');
              meta.setAttribute('name', 'viewport');
              document.head.appendChild(meta); viewports.push(meta);
            }
            for (var n = 0; n < viewports.length; n++) {
              var element = viewports[n], current = element.getAttribute('content') || '';
              var parts = current.split(/[;,]/).map(function(part) { return part.trim(); }).filter(function(part) {
                var key = part.split('=')[0].trim().toLowerCase();
                return part && key !== 'user-scalable' && key !== 'minimum-scale' && key !== 'maximum-scale';
              });
              parts.push('user-scalable=yes', 'minimum-scale=0.25', 'maximum-scale=5');
              var next = parts.join(', ');
              if (current !== next) element.setAttribute('content', next);
            }
          }
          var observer = new MutationObserver(refresh);
          observer.observe(document, {subtree:true, childList:true, attributes:true, attributeFilter:['name','content']});
          window.__onvyraWebZoomPolicy = {refresh:refresh};
          refresh();
        } catch (_) {} })()
    """.trimIndent()
}
