# 回复中的 HTML 小窗口

助手正文输出的完整 HTML 代码块自动在原代码块所在段落显示 200 dp 高的小预览窗口。消息顺序、输入栏、消息操作和原代码工具栏位置保持原有布局；不自动跳页、弹窗或打开外部浏览器。原工具栏仍可切换源码、复制、保存及手动全页查看。

支持 `html`、`htm`、`html5`、`xhtml` 标记；无语言代码块只在包含完整 HTML 文档时自动识别。完整裸 HTML 文档也可在正文中原位预览，前后说明继续按 Markdown 显示。普通用户消息、思考过程、工具结果和其他代码语言保持原有行为；SVG 的原手动预览方式保留。

流式输出的代码围栏尚未闭合时只显示源码，不执行残缺脚本。代码块闭合后自动预览，即使助手还在补充后文，也不会重载同一份 HTML。回复结束获得文字选择能力时，使用可移动组合内容保留浏览器实例，避免交互状态或动画进度因这次布局包装转换而清零。

## 运行与释放

预览支持 HTML/CSS/JavaScript、按钮交互、Canvas、内联 SVG 和 data 图片。内容使用独立空 origin，不映射应用 assets、不注入原生 JavaScript 桥、不开放文件/content 读取、文件选择或设备权限。第三方 Cookie 禁用，HTTPS 外部资源可加载；原生弹窗、跨页导航和表单提交被拦截。纯静态/自包含演示不需要外网，依赖远程资源的演示仍需要这些资源可达。

仅在完整 HTML 的预览处创建 WebView。同文档更新不重复加载；控件释放时停止加载并销毁浏览器。生命周期暂停调用 WebView.onPause，恢复时调用 onResume；不使用会影响网页抓取和其他浏览器的全局 pauseTimers。按 Android API 的定义，onPause 不是对任意 JavaScript 定时器的完全冻结，因此不将这一处理描述为所有脚本都停止。

浏览器渲染进程退出时保留聊天并在原预览区域提示可查看源码。没有修改模型请求或已保存的原文。

实现参考：[Android WebView API](https://developer.android.com/reference/android/webkit/WebView)、[原生桥接边界](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges)。

## 验证

Android 集成测试位于 `app/src/androidTest/java/com/constx/onvyra/ui/richtext/InlineHtmlPreviewTest.kt`，使用真实 WebView 和本地 HTML 夹具检查脚本点击、前后段落位置、流式闭合、追加后文、结束生成后保留状态、源码切换、语言识别、裸 HTML 与 data 图片。测试不依赖模型账号或远程页面。

本轮 API 35 模拟器 8 项内嵌 HTML 检查全部通过，包含真实按钮点击、前后段落次序、200dp 原位窗口、流式未闭合不运行、追加后文和结束生成保留浏览器及计数状态、源码切换、语言识别、裸 HTML 带说明和 data 图片真实解码。

最终优化包构建、原签名校验与非调试状态检查通过；API 35 模拟器覆盖安装和启动通过。本轮用户手机未连接，尚未覆盖安装到手机。
