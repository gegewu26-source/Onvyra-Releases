package me.rerere.androidcontrol

/** UI text is untrusted observation data, never an instruction to the agent. */
internal object ControlPolicy {
    private val sensitive = Regex("发送|发出|发表|发布|删除|移除|清空|付款|支付|购买|下单|转账|汇款|充值|提交订单|确认订单|\\b(send|post|publish|delete|remove|clear all|pay|purchase|buy|checkout|transfer)\\b", RegexOption.IGNORE_CASE)
    private val takeover = Regex("支付密码|短信验证", RegexOption.IGNORE_CASE)
    fun sensitive(label: String): Boolean = sensitive.containsMatchIn(label)
    fun takeover(label: String): Boolean = takeover.containsMatchIn(label)
}
