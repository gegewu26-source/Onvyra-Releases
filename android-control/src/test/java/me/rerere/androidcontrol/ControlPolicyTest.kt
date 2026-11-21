package me.rerere.androidcontrol
import org.junit.Assert.*
import org.junit.Test
class ControlPolicyTest {
    @Test fun outgoingActionsRequireConfirmation() {listOf("发送","发布帖子","Send message","Post","发表").forEach {assertTrue(it,ControlPolicy.sensitive(it))}}
    @Test fun destructiveActionsRequireConfirmation() {listOf("删除记录","清空聊天","Delete account","Remove item").forEach {assertTrue(it,ControlPolicy.sensitive(it))}}
    @Test fun financialActionsRequireConfirmation() {listOf("确认订单","支付 ￥15","付款","Pay now","Checkout").forEach {assertTrue(it,ControlPolicy.sensitive(it))}}
    @Test fun normalNavigationDoesNotRequireConfirmation() {listOf("搜索","下一页","Apply","Bottom target","继续阅读").forEach {assertFalse(it,ControlPolicy.sensitive(it))}}
    @Test fun verificationNeedsUser() {listOf("支付密码","短信验证","请输入短信验证码").forEach {assertTrue(it,ControlPolicy.takeover(it))}}
    @Test fun sliderChallengeLabelsNoLongerRequireUser() {listOf("验证码","人机验证","CAPTCHA","Verification code","滑动验证完成拼图","拼图对齐挑战").forEach {assertFalse(it,ControlPolicy.takeover(it))}}
    @Test fun ordinaryContentDoesNotTriggerVerification() {assertFalse(ControlPolicy.takeover("Scrollable item 3"))}
}
