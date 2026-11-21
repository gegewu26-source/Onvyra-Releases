package com.constx.onvyra.ui.pages.netdisk

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Fixed, one-way official-page adapter. No JavascriptInterface, remote code, or returned page content. */
internal object NetDiskAuthPageBridge {
    fun submit(account: String, secret: String, sms: Boolean, sendCode: Boolean, consent: Boolean): String {
        val payload = buildJsonObject {
            put("account", account.trim()); put("secret", secret); put("sms", sms)
            put("sendCode", sendCode); put("consent", consent)
        }.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        return TEMPLATE.replace("__ONVYRA_INPUT__", payload)
    }

    private val TEMPLATE = """
        (() => {
          const p = __ONVYRA_INPUT__;
          try {
            const visible = e => {
              if (!e) return false;
              const s = e.ownerDocument.defaultView.getComputedStyle(e), r = e.getBoundingClientRect();
              return s.display !== 'none' && s.visibility !== 'hidden' && Number(s.opacity || 1) > 0 && r.width > 1 && r.height > 1;
            };
            const docs = [document];
            for (let i = 0; i < docs.length && i < 8; i++) {
              for (const f of docs[i].querySelectorAll('iframe')) {
                try { if (f.contentDocument && docs.indexOf(f.contentDocument) < 0) docs.push(f.contentDocument); } catch (_) {}
              }
            }
            const all = selector => docs.flatMap(d => Array.from(d.querySelectorAll(selector))).filter(visible);
            const text = e => (e.innerText || e.textContent || e.value || e.getAttribute('aria-label') || '').replace(/\s/g, '');
            const hint = e => [e.type,e.name,e.id,e.placeholder,e.autocomplete,e.getAttribute('aria-label')].join(' ');
            const inputs = () => all('input').filter(e => !e.disabled && !['hidden','checkbox','radio','submit','button'].includes(e.type));
            let fields = inputs();
            const password = fields.find(e => e.type === 'password' || /密码|password|passwd/i.test(hint(e)));
            const otp = fields.find(e => /验证码|动态码|短信|otp|verifycode|verification|smscode/i.test(hint(e)) && e !== password);
            if ((p.sms && !otp) || (!p.sms && !password)) {
              const desired = p.sms ? /^(短信|手机验证码|验证码|免密)(登录|登陆)?/ : /^(账号|帐号|账户|密码)(密码)?(登录|登陆)/;
              const toggle = all('button,a,[role=tab],span,div').find(e => e.childElementCount < 2 && desired.test(text(e)) && text(e).length < 18);
              if (toggle) { toggle.click(); return 'mode_changed'; }
            }
            fields = inputs();
            const pass = fields.find(e => e.type === 'password' || /密码|password|passwd/i.test(hint(e)));
            const code = fields.find(e => /验证码|动态码|短信|otp|verifycode|verification|smscode/i.test(hint(e)) && e !== pass);
            const account = fields.find(e => e !== pass && e !== code && /手机|邮箱|账号|帐号|用户名|phone|mobile|email|passport|username|loginname/i.test(hint(e)))
              || fields.find(e => e !== pass && e !== code && ['text','tel','email','number'].includes(e.type));
            if (!account) return 'account_missing';
            const set = (e, value) => {
              e.focus();
              const w = e.ownerDocument.defaultView;
              const setter = Object.getOwnPropertyDescriptor(w.HTMLInputElement.prototype, 'value').set;
              setter.call(e, value);
              e.dispatchEvent(new w.Event('input', {bubbles:true}));
              e.dispatchEvent(new w.Event('change', {bubbles:true}));
              e.blur();
            };
            set(account, p.account);
            if (!p.sendCode) {
              const target = p.sms ? code : pass;
              if (!target) return p.sms ? 'code_missing' : 'password_missing';
              set(target, p.secret);
            }
            if (p.consent) {
              for (const c of all('input[type=checkbox]')) {
                const label = c.closest('label') || c.parentElement;
                const words = text(label);
                if (!c.checked && /服务协议|用户协议|隐私政策|隐私协议|用户服务|服务条款|使用条款/.test(words)
                    && !/营销|广告|推广|邮件订阅/.test(words)) c.click();
              }
            }
            const targets = all('button,input[type=submit],a,[role=button],div,span').filter(e => e.childElementCount < 3);
            const rx = p.sendCode ? /^(获取|发送|重新获取|重新发送)(短信)?(验证码|动态码)/ : /^(登录|登陆|立即登录|立即登陆|验证并登录|确认登录|登录\/注册|登录注册)$/;
            const button = targets.find(e => rx.test(text(e)) && text(e).length < 18);
            if (!button) return p.sendCode ? 'send_code_missing' : 'submit_missing';
            if (button.disabled || button.getAttribute('aria-disabled') === 'true') return 'form_filled';
            button.click();
            return p.sendCode ? 'code_requested' : 'submitted';
          } catch (_) { return 'page_not_ready'; }
        })()
    """.trimIndent()
}
