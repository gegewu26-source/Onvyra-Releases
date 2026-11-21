# 思考档位与聊天模型记忆

## 用户这几个模型的规则

匹配使用模型 ID，不使用可随意修改的显示名称。下列反代别名已明确识别；请求的 `model` 字符串保持原样，不偷偷替换模型或路由。

| 反代模型 ID | 可选档位 | 旧配置里不支持的档位 |
| --- | --- | --- |
| `deepseekv4.1f` | 关闭、自动、低、高、最大 | 中→高，极高→高 |
| `gpt6a` | 自动、低、中、高、极高、最大 | 旧关闭→低 |
| `6.1sol`、`gpt6.1sol` | 自动、低、中、高、极高、最大 | 旧关闭→低 |
| `6luna`、`gpt6luna` | 关闭、自动、低、中、高、极高、最大 | 全部已支持 |
| `glm5.3`、`glm5.3flash` | 自动、低、高、最大 | 旧关闭→低，中→高，极高→最大 |

这里的 GPT 简写按用户给出的名称明确对应 GPT-6 Astra、GPT-6.1 Sol、GPT-6 Luna；若反代把同名路由到另一种模型，客户端无法仅凭名称发现其真实上游。

关闭与低档是两回事。不能关闭的已确认型号不显示可选择的“关闭”，提示会说明低档仍可能思考。旧聊天/备份中已保存的无效档位会显示映射结果，真实编码也使用相同规则；不只隐藏界面上的思考文字。自动不强制深度参数，保留接口与模型自定义的默认；默认不一定关闭。

DeepSeek 自建 `/v1/chat/completions` 关闭发送 `thinking.type=disabled`，不发送 `reasoning_effort`。开启发送 `thinking.type=enabled` 和对应 low/high/max。GLM5.3 系列只发送 enabled 与 low/high/max，旧关闭会映射为最低档；GLM5.2支持关闭和high/max，GLM5.1等已确认的旧型号仅区分开启/关闭，不伪造多个深度档。

GPT 显式档位通过 Chat Completions 的 `reasoning_effort` 或 Responses 的 `reasoning.effort` 发送。已确认型号启用思考时会移除该型号不允许的采样参数，防止模型固定自定义参数使请求报错。显式档位优先于固定思考自定义字段，其他自定义字段保留；自动仍使用自定义默认。

原生 Gemini 按型号使用合法 thinkingLevel/thinkingBudget；不支持关闭的型号不再将“minimal”宣传成关闭。原生 Claude 按型号区分旧 enabled+budget 与支持的 adaptive+effort，不给旧型号发送不支持的 adaptive。

未知自定义模型继续使用兼容方式，并明确提示支持能力取决于接口。不会宣称任意私有别名已验证。服务商广告的 `effort.supported_levels` 可用于兼容 effort 接口的活动档位，不从缺少 none 推断不能关闭，不让该元数据给二值模型虚构深度。

## 生效时点及新聊天

滑块位置与原布局保留，只展示当前已确认模型支持的选项。在聊天里切换模型后，打开中的滑块同步更新支持集与当前有效值。

选择配置后立刻发消息，也必须等该次设置保存完成后读取设置。正文、标题、翻译等旧会话快照不能把较新的思考档位、模型、搜索或注入选择覆盖回去。已经发送的请求及它的工具续轮保持本次配置，修改作用于下一次生成，不追溯修改已发送的请求。

显式选择聊天模型后，该助手后续新聊天继承这个选择；已有聊天持有各自的模型配置，不被其他聊天切换模型连带改写。普通思考或扩展修改不能用旧聊天的模型覆盖较新的模型默认。此行为与现有逐助手扩展默认保持一致。

档位控制的是模型思考力度或预算，不是固定秒数；相同问题、不同服务负载和模型本身仍会造成耗时差异。是否显示思考文本也不能单独证明服务器执行了指定档位。

## 官方依据

- [DeepSeek 思考参数](https://api-docs.deepseek.com/guides/thinking_mode/)、[模型目录字段](https://api-docs.deepseek.com/api/list-models/)
- [OpenAI reasoning](https://developers.openai.com/api/docs/guides/reasoning)、[GPT-6 Luna](https://developers.openai.com/api/docs/models/gpt-6-luna)、[请求兼容要求](https://developers.openai.com/api/docs/guides/deployment-checklist)
- [GLM Chat 参数](https://docs.z.ai/api-reference/llm/chat-completion)、[GLM-5.3](https://docs.z.ai/guides/llm/glm-5.3)、[Flash 系列](https://docs.z.ai/guides/vlm/glm-5.3-flash)
- [Gemini thinking](https://ai.google.dev/gemini-api/docs/generate-content/thinking?hl=en)
- [Claude thinking](https://platform.claude.com/docs/en/build-with-claude/thinking)、[effort](https://platform.claude.com/docs/en/build-with-claude/effort)

## 验证边界

本轮先验证客户端档位、存储、界面和真正发出 HTTP 前的请求体，覆盖用户模型原 ID、全部旧档位、流式与非流式。拦截器在 DNS 前返回受控响应，不使用用户密钥或调用用户真实模型。参数进入请求不等于反代和真实上游执行，需要服务端或真实端到端结果才能进一步确认。模拟器也不能代替用户手机使用验收。


## 本轮验证完成（2026-10-20）

137项相关单元检查通过（11类、0失败、0忽略）：共享能力与OpenAI/GLM/Google/Claude编码65项、Google Interactions11项、会话配置并发8项、ChatService13项、ConversationConfig含扩展默认18项、会话17项、翻译保存状态5项。

原目录包含中文时，Gradle单元测试工作进程仍在类发现阶段失败；已用本次Gradle刷新后的依赖、当前编译的模块类及GBK参数文件运行器执行上述单元检查。该环境问题与测试断言失败区分记录，不将Gradle原生测试任务声明为通过。

API35任务模拟器27项检查通过：思考请求/滑块/新聊天模型继承12项，翻译请求与稳定界面13项，扩展默认与选择2项。用户提供的6个实际模型ID均按全部7个已保存档位、流式及非流式验证真实生成链至HTTP发送前请求；同时覆盖固定自定义参数、切换模型后的滑块范围、设置落盘独立重读、立即发出第一条消息、既有聊天隔离和旧快照保存保护。UI使用语义滑块操作验证回调，未宣称手机手指拖动实测。

Debug、Android测试包、optimized优化包均构建通过；最终优化包保持原包名、版本与签名，未开启DEBUGGABLE，模拟器覆盖安装及启动正常，启动崩溃缓冲无异常。本轮没有调用用户真实反代，也没有覆盖安装到手机。任务创建的模拟器已关闭，其临时AVD已清理；小体积验证日志保留在app/build/tmp/reasoning-repair。
