# Android 手机操控

本实现只移植手机控制内核，继续由 Onvyra 原有模型、会话任务和工具审批执行。
Agent 和工具执行器就在手机 App 内，不存在把系统 API 暴露给远端服务器的未认证接口。
模型服务商仍是用户当前选择的服务商；本模块不会另建模型账号或后台控制服务器。

## 入口与授权

1. 打开「设置 → 手机操控」，先在两个独立入口中选择「无线调试模式」或「Root 模式」。选择入口不会启动助手或请求 Root。
   无线调试模式集中显示系统无线调试、通知配对、连接端口及 USB 备用启动；Root 模式只显示 Root 启动方式。
   两种模式内分别保留共同的总开关、无障碍授权、预览、接管和日志；总开关默认关闭。开启后允许本应用发送通知，运行中保留停止入口。
   点击页面返回键或系统返回键可回到模式选择页，页面不会自动替换启动方式。
2. 在系统无障碍设置里，由本人开启「Onvyra 手机操控」。模块不会自动替用户修改该授权。
   系统若提示受限制设置，应在本应用的系统应用信息页按系统指引处理，再回来开启。
   页面分别检查系统保存的授权和服务的实际连接。出现「已授权但服务已断开」时，
   请在系统中关闭后重新开启该服务；仅在系统授权列表里出现，不代表已经能执行操作。
3. 选择一种助手启动方式。已 Root 的手机可点击「Root授权启动（无需Wi-Fi）」，由本人在 Root 管理器中批准；
   未 Root 的手机使用下述通知配对，或使用电脑 USB 启动命令。Root 是可选方式，不是模块的前提。
4. 使用通知配对时，在「无线调试模式」中点「开始通知内配对」，允许通知，然后到系统开发者选项开启无线调试。
   此启动方式需要 Android 系统允许无线调试，通常需要连接 Wi-Fi。
5. 在系统中点「使用配对码配对」，**保持这个弹窗打开**，下拉通知栏，点击配对通知的「输入配对码」，再输入当前六位码。
   输入框和键盘由 Android 通知系统管理；下拉通知栏不保证自动展开输入框，仍需点击回复操作。
   只需输入码，不必手动抄配对端口；应用通过 mDNS 查找属于本机的配对端口。
   不要整屏切回应用填码：Android 设置页暂停时可能关闭这个一次性配对服务。
6. 配对成功后自动寻找本机无线调试的连接端口并启动助手。
   「已配对但连接失败」与「配对失败」分开显示；前者可回应用的「无线调试连接备用」填写系统无线调试首页的连接端口重试。
   备用连接地址固定为 `127.0.0.1`。这里填写的是首页的**连接端口**，不能填六位码弹窗的**配对端口**。
   原来的分屏手动配对表单已移除。
7. 确认「操控状态」中显示「已连接」及无障碍已开启。返回聊天，下一次生成会自动注册十个 AndroidControl 工具。

通知配对仅在本人启动后运行，发现等待有时限，可在通知中取消。配对码不保存、不记入日志。
总开关关闭或点击全局「立即停止」会停止配对等待和正在进行的网络连接；已完成的配对不会因此删除。
撤销配对仍使用原来的明确撤销按钮。
Root 启动只在本人点击按钮后请求授权，不在应用启动、断线重试或后台自动调用 `su`。
它仅用于启动同一个 `app_process` 辅助进程；辅助进程仍以 Android Shell **UID 2000** 运行，
保留签名、一次性口令及 Binder 调用者校验，不向 Agent 增加任意 Root 执行入口。
本模块不修改 Root 管理器策略、模块、SELinux 设置或其他应用权限。
当前 Root 启动参数把目标用户放在 `-c` 前：`su -g 2000 -- 2000 -c <固定启动命令>`。
已在实体手机核对过该面具版本的参数行为：把 UID 放在 `-c` 后可能被当作命令参数转交，
导致实际仍为 UID 0；修正后进程报告 UID 2000，SELinux 上下文仍由面具决定。
辅助进程不会因这种上下文差异而放宽 UID、签名或 Binder 校验。此前 Root 参数修正版已在实体手机关闭无线调试后，实际点击应用 Root 按钮并通过助手交接；另以进程 UID 检查确认是 UID2000。

另一种启动方式：在「无线调试模式」页点「复制USB命令」，电脑连接这台手机并获得 USB 调试授权后执行。
命令形如 `adb shell sh /sdcard/Android/data/<当前应用包名>/files/android-control-usb-start.sh`。
它以 ADB Shell（UID 2000）运行相同的 `app_process` 辅助进程，不需要 `su` 或真正 Root。
脚本的一次性启动口令有时限；过期时重新打开设置页复制命令。应用更新后也应重新生成。
正式应用使用 `com.constx.onvyra` 包名；优化包不可调试。旧包与新包是不同应用，需先导出备份再导入，保留旧应用直到核对数据。

助手建立后，界面读取、点击、输入和滑动都在手机本地执行，不需要每一步连接 Wi-Fi；
模型调用是否联网仍取决于原来的模型服务。手机重启或助手进程退出后，需要重新启动助手。
Root 启动可再次显式点击按钮；无线启动则可能需要恢复无线调试及重新连接。
无线调试关闭或端口变化也可能使无线连接失效。已成功的本机连接端点会保存；
发生断线且总开关仍打开时，最多按 1/3/7 秒间隔重新认证三次，失败显示状态，绝不扫描其他设备。
不会替用户打开无线调试。端口改变时需手动填入新连接端口；已配对的私钥由 Android Keystore 包装保存。
重新连接只恢复权限桥，**不会自动重放中断的点击或恢复旧批量任务**。

## 工具契约

工具仅在总开关打开后加入原有 Agent；不同聊天以会话 ID 隔离，同一时刻只有一个控制任务。
其他聊天会收到 `task_owned_by_another_conversation`，不能操作或停止该任务。

| 工具 | 主要参数 | 行为 |
|---|---|---|
| `open_app` | `app_name` 或 `package_name` | 从当前用户真实已安装的启动器应用中解析名称，在本任务虚拟屏打开；自动沿用手机主屏实际像素尺寸和 DPI |
| `get_ui_tree` | 可选 `find_text` | 精简控件列表、层级父 ID、文字、类型、坐标与动作标记 |
| `screenshot` | 无 | 仅截本任务的虚拟屏；需要时才发给支持图片输入的当前模型 |
| `tap` | `node_id` 或 `x/y`，可选 `duration_ms` | 无障碍点击优先；超过150ms为真实长按 |
| `input_text` | `text`，可选 `node_id` | 设置非密码输入框的 Unicode 长文本并核对完整读回 |
| `swipe` | `node_id/direction` 或 `x1/y1/x2/y2/duration_ms` | 无障碍滚动或原生滑动、拖动 |
| `press_key` | `key` | BACK、ENTER/CONFIRM、SEARCH、TAB、BACKSPACE |
| `wait_for` | `expect/expect_gone/expect_app`，`timeout_ms` | 事件驱动条件等待，最长60秒，可被停止打断 |
| `run_steps` | `steps` 数组 | 1至20个动作本地连续执行，不逐次请求模型 |
| `stop_task` | 无 | 停止本聊天的操作并释放屏幕、Surface和帧队列 |

`open_app` 可以直接使用系统里显示的应用名，例如 `{"app_name":"微信"}`；旧的完整包名参数继续支持。
名称解析仅查询当前 Android 用户可见、实际已安装且有启动入口的应用，不凭模型猜包名。
精确标签和包名优先，微信 / WeChat、QQ 别名也必须匹配本机真实安装的包；部分名称匹配到多个应用时，
返回 `app_name_ambiguous_choose_package` 和 `app_candidates`，让 Agent 根据真实候选选择完整包名。
找不到时返回 `app_launcher_not_found`，不会为了失败的名称先创建空虚拟屏。

返回值统一包含 `ok`，正常观察带 `task_id/display_id/screen`，失败带机器可识别的 `error`。
控件 ID 来自当前显示屏的窗口、结构路径和身份属性，保持不变的控件重复读取会保留短 ID；
界面替换、复用列表行或坐标改变后旧 ID 会失效。执行前重新取得真实节点并核验，不能编造 ID。
图像不可识别不能被当成动作成功，文字输入也不会仅凭 ACTION_SET_TEXT 返回值宣告成功。

示例（应先通过 `open_app` 和实际 UI 树取得包名和节点 ID）：

```json
{"steps":[
  {"tool":"input_text","args":{"node_id":"<实际输入框ID>","text":"你好，Android"}},
  {"tool":"tap","args":{"node_id":"<实际按钮ID>"},"expect":"<期望出现的文本>","timeout_ms":5000},
  {"tool":"swipe","args":{"node_id":"<实际滚动区ID>","direction":"down"}}
]}
```

## 聊天内权限模式

聊天输入框的思考按钮右侧为手机操控权限图标：手掌是「变更前确认」，盾牌勾选是「自主模式」，盾牌感叹号是「完全访问」。
完全访问会取消手机操控动作的逐次确认；自主模式仍会询问敏感动作和无法确定目标的坐标点击；变更前确认对所有改变界面的操作询问。
读取、等待和停止无需确认。选择按当前聊天保存，并供新聊天继承；不同聊天不互相改变已保存档位。
这项设置只改变本应用手机操控的确认流程，不增加系统权限，也不会移除受保护页面接管或虚拟屏隔离。

## 实时操控预览小窗

在「设置 → 手机操控」里可切换「显示操控预览小窗」，该预览选项默认开启；手机操控总开关仍默认关闭。
助手与无障碍服务就绪、成功打开任务的虚拟屏后，主屏上会显示该任务的画面。
画面外不再额外堆叠黑色顶栏或底栏，三个点和底部横杠直接叠在画面上。拖动顶部三个点区域移动小窗，松手后保留位置，不再自动贴边；重新展开或打开时恢复保存的位置。
长按底部横杠后上下拖动可按比例缩放；宽高由同一比例计算，达到屏幕边界时一起限制，完整保留页面内容。向上拖到最小尺寸后松手，会收起到主屏右上角；点击「AI」恢复这次拖动前的尺寸和位置。取消手势不会触发收起。
点击三个点打开菜单，仅保留全屏、最小化、关闭三个线框图标。最小化会移到主屏右上角并继续任务；点击「AI」恢复原来的位置和尺寸，也可直接点击「停止」。关闭立即停止手机任务及其所属对话的当前 Agent 执行并释放虚拟屏，不需要先回设置页。

小窗通过本人已经授权的无障碍服务创建 `TYPE_ACCESSIBILITY_OVERLAY`，不新增
`SYSTEM_ALERT_WINDOW` 悬浮窗授权。窗口不获取主屏焦点，保留完整比例；小窗本身不转发点击到目标应用。
小窗本身允许系统截屏和录屏，不设置 `FLAG_SECURE`；目标应用自己的受保护窗口仍遵守系统限制。
默认小窗高度不超过主屏可用高度的40%，旧版保存的过大尺寸会迁移一次，后续仍可主动拖动放大。

展开时优先把本任务虚拟屏的输出 Surface 接到小窗的 TextureView，画面由系统和渲染线程连续合成，
不再把每帧转成 Bitmap、缩放后逐张替换。实际刷新率取决于目标应用、设备渲染和系统调度，不承诺固定帧率。
缩放时固定虚拟屏源缓冲区的分辨率，让 TextureView 与窗口同时测量并按比例显示，避免外框放大后右侧、底部仍是空白。拖动期间跳过无变化的窗口和控件尺寸更新。
截图位图转换与截图后的窗口校验使用后台执行器；`get_ui_tree` 返回本次新读的控件树，避免没有操作时再重复扫描整页。
打开应用成功后立即启动预览，不再等待完整控件树或 OCR 完成；首次授权画面暂不可用时按截图限流节奏重试，正常展示后的保护检查周期不变。
原生输出建立后，约每秒一次的受保护窗口截图只检查是否允许展示，检查得到的位图立即释放，不用于视频传输。静止界面在首个原生缓冲到达前，允许保留一张不超过360像素宽的受保护首帧；原生帧到达后清除。
首次检查未通过、检查过期、密码 / 验证页面、锁屏和无障碍断开时，小窗停止原生输出并清空画面。
虚拟屏保持 NONSECURE，系统仍会遮蔽受保护内容；没有开启安全显示屏能力来读取受保护图层。
Agent 的截图和 OCR 继续走原有受保护窗口 API，不读取预览的 Surface 或主屏。

旧助手不支持输出 Surface 绑定时，保留原有低频截图回退（约每500毫秒尝试一次、图宽不超过360像素），
不会把它宣称为视频流。收起、关闭、接管、停止或断线时，先把输出还给原来的 ImageReader 排空端，再释放小窗纹理。
预览在手机本地展示，不发送给模型、不写磁盘。新的 Binder 操作仍校验调用 UID 和本任务拥有的显示屏，拒绝主屏和已释放屏。

## 屏幕与输入边界

虚拟屏默认读取主屏实际宽、高和 DPI。
这能避免同一个应用进程缓存的主屏尺寸与虚拟屏资源配置不同，导致部分组件过大、过小或挤满整个页面。
AI 工具不提供分辨率和 DPI 参数；旧对话或批量步骤中模型传入的 `width/height/dpi` 也会在执行前移除。
预览缩放只改变悬浮窗，不会更改应用看到的虚拟屏配置。超过辅助进程支持的尺寸或 DPI 时，宽、高和 DPI 使用同一比例缩小，保留逻辑布局。
底层 AndroidControl API 仍支持完整且有效的宽、高、DPI 高级配置；不完整或超出支持范围的配置会明确报错，不再独立截断三个值。

辅助进程持有 `DisplayManager.createVirtualDisplay` 创建的正 displayId、应用传入的帧 Surface，
通过 `ActivityOptions.setLaunchDisplayId` 启动 Activity。采用 TRUSTED、OWN_CONTENT_ONLY、
OWN_FOCUS、STEAL_TOP_FOCUS_DISABLED 等标记，输入法使用该屏自己的策略。
输入、启动和释放只接受本辅助进程创建的屏号；屏0和任意外部屏号均不可操作。
用户正在主屏使用的 App 不会被挪走，返回 `app_in_use_on_main_screen`。
这复用同一 Android 用户下真实 App 的数据和登录态，不复制其数据库、Cookie、密码或账号凭据。

控件操作优先 Accessibility ACTION_CLICK / ACTION_SET_TEXT / ACTION_SCROLL，之后使用 Shell 身份
调用 InputManager 注入带 displayId 的 MotionEvent / KeyEvent。原生初始 DOWN 无法注入时，
支持固定参数的指定屏 `input` 点击、长按、直线滑动和白名单按键兜底。
已开始的原生手势不会整体重放，曲线或非匀速轨迹不会被悄悄替换成另一条手势。
停止会中断触摸等待或兜底进程，并清理当前触摸；不会向主屏发全局 HOME、电源或音量键。

读取通过 `getWindowsOnAllDisplays` 定位目标屏，不使用 `rootInActiveWindow` 退回主屏。
默认传给模型的观察经过精简；批量结果只带最后一份界面，避免每一步重复发送整棵树。
只有原生树缺少有用标签时才调用 APK 自带的 ML Kit 中文 OCR。它不调用视觉大模型。
需要画面时 `screenshot` 才返回图片；Android14+使用目标屏最上层应用窗口截图API，使系统强制检查FLAG_SECURE。
窗口截图带 `image_origin`；使用图片里的坐标点击时，要先加上这个原点，换算为虚拟屏坐标。
当前模型未开启图片输入时明确说明，继续依靠节点和 OCR。
密码字段的文字、描述、hint不导出，也不允许自动写入；系统禁止截图的页面不会用另一种 API 绕过。

## 停止、接管与确认

设置页顶部与会话常驻通知都有「立即停止」，原聊天的停止生成也会释放该聊天的手机任务。
总开关关闭会停止当前任务。撤销按钮还会销毁本地助手、一次性认证口令和本地配对密钥；
Android 系统侧已配对记录需由本人在无线调试设置中删除。

登录、验证码、支付密码和受保护页面应停止并让用户接管。设置页「接管到主屏」只在本人点击时
把本任务移到主屏，并先暂停正在执行的动作 / 条件等待。被接管打断的工具返回 `user_takeover_active`，
保留原任务和虚拟屏，后续 Agent 操作继续暂停；它不会被当作一般 STOP 自动销毁。
完成后先离开该 App 的主屏页面，再点「处理完毕，交还 AI」，将同一任务移回其原虚拟屏。
目标仍在主屏时返回 `leave_target_app_on_main_then_retry`，不会强行抢走当前应用。
显式交还只恢复本地任务，不自动重放接管前未完成的操作；Agent 应读取当前页面后决定下一步。
停止、撤销权限或助手断开仍终止任务，不能以接管恢复绕过重新授权 / 启动。
Agent 工具没有修改自身总开关或确认规则的入口，也不允许自动打开本控制器的权限设置。

默认开启「发送、删除、付款等敏感操作前确认」，使用原聊天的工具审批界面。
批准绑定聊天、任务、参数与观察指纹，只用一次；工具声明重建不会把未批准操作当成批准。
不能通过工具参数 `approved=true` 获取授权。未知坐标点击默认也需确认。
批量动作遇到敏感操作停在执行之前，由模型拆成单独工具调用请求确认，不继承整批批准。
新弹窗或意外换 App 会使批量任务停止并返回最新界面；不能继续执行旧计划。

日志保留最近200条动作状态并有界写入应用私有日志，不记录输入文字、页面原文、验证码、
启动口令、私钥或截图内容。辅助进程的 Binder 每次调用核对 App UID；初次交接额外校验
真实 UID/PID、当前 APK、一次性口令、Binder 所在进程和应用签名指纹，拒绝重放。

## 编译与兼容

主工程继续在原工程目录构建。新增 `:android-control` Android 库模块；两个 `*-fixture`
应用只用于测试，不是主应用运行依赖，也不打进主安装包。主应用仅增加该库、工具注册、
停止/审批接线及一个设置入口，不更换原有聊天界面、模型或任务调度。

推荐 Android 14+；后台操控在低于API34的系统上明确返回不支持，避免牺牲主屏隔离。
应用其余功能保持原最低API26要求。ADB配对协议依赖Android11无线调试，独立焦点引擎仍要求14+。
虚拟屏、无障碍和隐藏输入接口可能有厂商差异；拒绝副屏、FLAG_SECURE或无可操作节点的页面
返回实际错误，不能保证每一个第三方 App 都支持后台运行，也不会悄悄改用主屏。

本机实际使用 JDK 24（`D:\jdk24`）、Android SDK（`D:\sdk`）、Build Tools 37.0.0 和
NDK 29.0.14206865。已安装 `platforms/android-37.0` 与 `platforms/android-37.2`，
分别满足本模块的 AIDL 框架文件和主工程的 SDK 要求。构建命令应在原工程根目录执行。

验收时使用的初始化脚本位于本机工作目录的 `work/local-ndk.gradle`。
仓库内提供相同覆盖内容的可选脚本 [tools/android-control-local.gradle](../tools/android-control-local.gradle)，
仅在命令显式指定时把各 Android 模块的本机 NDK / Build Tools 选择设为上述版本，
不改主工程的插件版本、SDK 版本或源码位置。

```powershell
$env:JAVA_HOME = 'D:\jdk24'
$env:ANDROID_HOME = 'D:\sdk'
$env:ANDROID_SDK_ROOT = 'D:\sdk'

# 主库和与现有安装身份一致的优化 APK
.\gradlew.bat :android-control:assembleDebug :app:assembleOptimized `
  --init-script .\tools\android-control-local.gradle `
  '-Pandroid.overridePathCheck=true' --console=plain

# 编译 JVM 测试，以及独立模拟器使用的仪器测试和两个测试 App
.\gradlew.bat :android-control:compileDebugUnitTestKotlin :app:compileDebugUnitTestKotlin `
  :android-control:assembleDebugAndroidTest :android-control-fixture:assembleDebug `
  :android-control-mainfixture:assembleDebug `
  --init-script .\tools\android-control-local.gradle `
  '-Pandroid.overridePathCheck=true' --console=plain
```

本机 NDK 或 Build Tools 与此不同的环境可不传该脚本，按主工程要求准备依赖。
`android.overridePathCheck` 只允许继续在原中文路径中构建，不会修复 Java 测试进程的编码问题。

### Windows 中文路径下的 JVM 验收

标准入口是 `:android-control:testDebugUnitTest`，但本机 Windows 中文目录下，Gradle JUnit worker
的参数文件 / classpath 受系统编码影响，未能用该测试任务正常完成验收。
上面的 `compileDebugUnitTestKotlin` 只代表测试编译，不代表测试已经执行或通过。

源码包现提供独立 JUnit 执行工具，不依赖验收机器临时目录或旧 worker 参数。
先用可选初始化脚本导出两个模块当前 `testDebugUnitTest` 的完整运行 classpath：

```powershell
.\gradlew.bat :android-control:exportAndroidControlTestClasspath :app:exportAndroidControlTestClasspath `
  --init-script .\tools\android-control-local.gradle `
  --init-script .\tools\android-control-junit.gradle `
  '-Pandroid.overridePathCheck=true' --console=plain

python .\tools\run-android-control-junit.py
```

导出任务只执行 classpath 的构建依赖，不执行 Gradle 的 `Test` 任务；每个模块生成
`build/control-test-classpath.txt`（UTF-8）。可先为导出命令添加 `--dry-run` 检查依赖计划。
Python 从脚本所在仓库及 `JAVA_HOME` 获取路径，在 Windows 用系统代码页写 Java `@args`，
通过同目录的 `JunitSummaryRunner.java` 执行 JUnit 4，并启用断言。
它只选择 `android-control` 的 `*Test.class` 及主应用的 `AndroidControlApprovalsTest`，
不扫描或运行主应用其他测试，也不会连接设备。可加 `--module android-control` 或 `--module app` 单独执行。

每个模块的日志与结果 JSON 写入 `build/control-junit/`；两组汇总为工程根目录的
`build/control-junit-summary.json`。以退出码和 `JUNIT_SUMMARY` 的实际执行、失败、忽略数为准，
没有找到测试或缺少汇总时也会失败，不能把“编译成功”算成“执行通过”。

上一版 Root 参数修正版的独立启动器实测为模块 **76 项**与主应用审批 **4 项**通过，均无跳过；这是历史基线，不是当前预览版的测试计数。
这包含 OCR 坐标、取消连接、错误脱敏、一次性口令失效和无障碍就绪状态等用例。
该历史版本在 Root 启动参数顺序修正后重新构建，独立 JUnit 和优化 APK 探针曾复测。当前版的实际计数、完整哈希和范围，以 `outputs/` 对应制品的验收记录为准。
独立 JUnit 通过不等于 Gradle `testDebugUnitTest` 任务通过，无需移动原工程。

Windows 中文路径下 AIDL 的依赖文件存在系统编码问题；本模块直接调用相同的 SDK AIDL 编译器
生成标准 Binder 绑定，去除生成头中的机器路径后交给 Java 编译，保留 `.aidl` 为接口源。
无需搬动源码或建立第二份工程。编译产物不会把测试辅助程序打入主 APK。

## 验收记录与未验证范围

本次增加了预览窗口、实际应用名解析和接管中断 / 交还的测试。新增测试文件或构建成功不等于已经执行通过；
本次实际执行结果与 APK / 源码包哈希应查看 `outputs/` 中对应 Onvyra 制品的验收记录。
独立模拟器的结果不能代替用户手机上的 QQ / 微信完整消息操作验证。

以下是**此前 Root 参数修正版的历史基线**，用于说明已有验证范围，不代表本次更改后已自动重测：

- 核心手机操控端到端与本地 OCR 共 **7 项**：真实辅助进程、虚拟屏与 App、观察 / 输入 / 停止等
  流程，以及创建屏幕期间停止、中英文 OCR；不是只检查接口是否存在。
- 通知配对 **3 项**：真实通知 RemoteInput 接入 Android 原生配对服务，以及轮次隔离、取消等流程。
- 主应用真实依赖图、十个工具的开关注册和设置页停止按钮 **1 项**。
- 不可调试的优化 APK：USB 启动、UID2000 助手重新认证、停止及撤销的 **2 项框架探针**通过。
  独立冷启动、现有 ZIP/Python 资源加载和播放器布局回归也已通过。

优化包探针源码在 `tools/android-control-release-probe/`，使用原生 Android Instrumentation，
不把未混淆的调试测试运行库硬接到独立 R8 优化包上。设置相同 `JAVA_HOME/ANDROID_HOME`，
在专用 `StatsUsageTest` 模拟器（端口5556）装好同签名优化 APK 后执行：

```text
python tools/android-control-release-probe/build.py
python tools/android-control-release-probe/verify.py
```

脚本会核对专用模拟器名称和 Shell UID，拒绝操作其他设备；探针 APK、测试 App、测试权限设置
都不属于最终主 APK。此前恢复构建曾在连接的 Android 16 实体手机上覆盖安装并验证启动，
覆盖前后应用数据目录的 inode 保持不变。用户已亲自在系统中重新开启无障碍，
随后确认系统实际绑定了该服务；没有由测试自动授予实体手机的无障碍权限。

设置页此前把「系统保存授权」误显示成「服务已激活」，这一状态判断已修正。
工具也区分 `accessibility_not_authorized` 与已授权但服务未连接的 `accessibility_service_disconnected`，
避免服务崩溃后仍给出可操作的假象。

实体手机已通过原有密钥与证书的 TLS 检查、实际系统配对码弹窗的 TLS / SPAKE2 配对、
已配对 adbd 的 TLS 传输，以及通知输入配对码到原生配对服务的验证。
把无线调试首页的连接端口误用于配对，可以复现截图中的 `TLS_FAILED`；这说明一种可复现原因，
不代表已经证明用户此前一定填错端口。

通知配对后的自动连接发现过 mDNS 端口发现失败；此前版本已修复多地址匹配和取消后的资源释放，
真机已记录到本机配对及连接端口成功发现。完整非 Root 无线自动启动后的 App 操作仍未在最终实体包验收。
最终实体包已在关闭无线调试时，实际点击应用 Root 按钮并连接 UID2000 助手，且无障碍保持实际连接。
另通过同一 ShellService 在面具域中创建、释放虚拟屏，验证主屏焦点没有变化。
实际 QQ / 微信发送消息的完整流程尚未验收，不以配对、构建或模拟器结果代替。
模拟器结果也不证明各厂商的后台限制与虚拟屏行为完全相同。

此前部分观察到的应用退出发生在以主应用为目标包的 `am instrument` 诊断期间：
Android 会重新启动目标包来装载测试，且默认 UiAutomation 会压制其他无障碍服务。
后续实体手机 UI 检查改用诊断包自身为目标，并设置 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，
不再为读状态而重启 Onvyra 或压制其无障碍服务。
另已修复停止 / 取消与前台服务启动交错时的竞态；这不能证明手机上所有闪退都来自同一原因。
完整 QQ / 微信消息工作流尚未验收，此前验收没有通过测试发送实际消息。

主应用启动检查、最终优化 APK 的实际运行、签名及归档哈希，以 [outputs](../outputs/) 中
本次手机操控最终验收记录为准；本节不以构建成功推断这些检查通过。

参考、许可、修改说明和原始版权声明见 [android-control/NOTICE.md](../android-control/NOTICE.md)。
本次实际测试结果与未验证范围见安装包旁的手机操控验收记录；构建成功不等于所有设备、
所有 App 或后台调度情形已经验证。
