2026-10-29：Token 统计与缓存命中率

在原统计页的缓存卡里增加缓存命中率，缓存命中 Token 和比例始终可见，不移动其他卡片。比例使用未缩写的整数总量：已记录缓存命中 Token ÷ 已记录输入 Token × 100%，保留一位小数。输入已经包含缓存部分，不能将缓存再次加到输入；输出不参与分母。多次请求按总 token 加权，不平均各次百分比。没有输入数据或缓存大于输入等不一致情况显示“—”。

校正的计算：
1. Gemini 原生 GenerateContent 的输入还应包含 toolUsePromptTokenCount；现与 promptTokenCount 一起归入输入，输出仍为 candidatesTokenCount + thoughtsTokenCount，缓存只取 cachedContentTokenCount，不重复相加。流式与非流式共用解析器。
2. Claude 原生流式 usage 的输入、缓存读取、缓存写入、输出可以分段报告。现逐字段保存累计状态，缺失/null保留，明确0覆盖，再合成完整快照。工具多轮及 pause_turn 续传仍按独立请求累计一次。
3. 分支聊天复制的历史保留同一消息ID，不再把这些复制记录的用量重复相加；同ID保留最大累计快照。重新生成且ID不同的真实回答仍计入；旧的无ID记录按各条记录计入。
4. SQLite 使用64位总和；坏JSON、非对象条目以及非法负数/计数类型不会拖垮或扣减统计。

已核对未发现该口径错误的部分：OpenAI兼容接口已有嵌套cached_tokens、顶层cached_tokens、DeepSeek prompt_cache_hit_tokens适配，缓存均为输入的一部分；OpenAI/Claude输出已包含思考，不另加reasoning明细；Gemini的思考需与候选输出相加。流式累计快照不反复求和，独立工具请求才累加。

边界：这是当前保存聊天记录中的接口上报用量，不是服务商账单。删除记录、未保存请求、翻译/标题等未落在聊天消息中的调用不会由此恢复为全账户消耗。旧请求已经漏算且没有保留原始usage的数据无法凭空补回；复制后同ID有多个独立续写分支的旧记录也不具备完整请求账本。接口未上报缓存时，显示的是已记录比例，不能据此确认真实缓存命中率。K/M/B展示有四舍五入，命中率使用原始整数计算。

验收：统计与用量相关43项测试全部通过（26项AI用量、6项比例算法、11项Android数据库/界面测试）；优化构建通过。更广AI回归316项中315通过，1项旧Volc Responses思考档位断言不匹配，已核对其请求映射源文件与测试与上次交付一致，本轮未改该行为。Android测试使用独立模拟器和合成数据，未调用用户付费模型接口。2026-10-29已同签名覆盖安装到连接手机；安装前后、启动应用之前的聊天数据库与配置文件哈希一致。

官方口径参考：
Google：https://ai.google.dev/api/generate-content#UsageMetadata
Anthropic：https://github.com/anthropics/anthropic-sdk-python/blob/main/src/anthropic/types/message_delta_usage.py
Anthropic流式合并：https://github.com/anthropics/anthropic-sdk-python/blob/main/src/anthropic/lib/streaming/_messages.py
DeepSeek：https://api-docs.deepseek.com/api/create-chat-completion/
