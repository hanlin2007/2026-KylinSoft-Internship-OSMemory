# OS Memory 开发计划（4 阶段并行交付版）

> 分支：`api-key-warning`（阶段 1 修复 + 阶段 2 在本分支完成，由本人手动 commit）
> 权威参考：`OS_Memory_初步调研与系统设计_修改版.pptx`（一切设计以 PPT 为最终标准）
> 工程基线：Android 原生应用形态（Kotlin + XML View，无 Compose），单 `:app` 模块
> 技术决策（2026-08-05 确认）：Room 数据库 · 云端大模型替代端侧小模型（双通道，杜绝规则引擎）· 精致控制台 + 示例数据一键装载
> 模型配置：BaseURL `https://api.ppio.com/openai/v1`（⚠️ 必须含 `/v1`，真实终端 `https://api.ppio.com/openai/v1/chat/completions`）· Model `deepseek/deepseek-v4-flash` · API Key 见 `core/ModelConfig.kt`（2026-08-06 轮换，演示用，提交前建议再轮换）

---

## 总体路线

| 阶段 | 主题 | 核心交付物 | 验收测试点 |
|---|---|---|---|
| 阶段 1（本轮） | 最小系统 | 依赖配置 · Room 数据层（记忆卡/日志/应用登记）· 记忆处理流水线（收集→净化→安全门控→LLM 结构化抽取→去重→入库）· 云端模型双通道 · 控制台 V1（记忆库列表/添加/删除 + 日志三板块 + 示例数据装载）· 全套文档 | 添加记忆→LLM 抽取→入库；日志出现"传入/推理"两条流水；示例数据装载；敏感词命中自动标敏感级 |
| 阶段 2 | 控制台完整版 + 检索/画像 | 记忆画像三板块（用户画像/风格偏好/工作项目 + 遴选标签）· 语义检索（关键词召回 + LLM 重排，`get_memo` 接口落地）· 日志"检索"板块联动 · 记忆修改流程（先画像后改）· 模型设置页（可改 baseUrl/key）· 审计导出 | 检索"跨应用回忆"类问题返回相关记忆；画像页生成可讲的三板块摘要；每次检索留"检索"日志 |
| 阶段 3 | vibe 三应用 + 系统 API 封装 | 记事本（只存记忆）· 对话问答（读写，`get_memo` 注入上下文）· 文件分类器（读记忆自动生成分类）· MemoryService 接口文档化（memo_collect / get_memo 形态） | 记事本记一段话→控制台立即可见；对话应用回答引用记忆；文件分类器开启记忆后自动分类 |
| 阶段 4 | 进阶整合 + 演示打磨 | AutoDream 后台整合（LLM 驱动冲突/遗忘/整合 + 降权）· 取记忆安全弹窗（用户确认）· Binder/AIDL 跨进程服务形态 · 网络策略（Local/Cloud 双记忆树不冲突合并）· 演示脚本与话术文档 · 全量 QA | 完整演示脚本跑通：跨应用记忆 → 画像 → 检索 → 整合 → 审计，全程安全门控可见 |

每阶段完成后：更新本计划状态 → 汇报"本轮可交付功能 + 如何测试" → 等待检查。

---

## 阶段 1 详细清单（本轮交付）

### 1.1 Gradle / 工程
- `gradle/libs.versions.toml`：新增 `room = "2.7.2"`、`ksp = "2.2.0-2.0.2"`、`okhttp = "4.12.0"` 及对应 library/plugin 条目
- `app/build.gradle.kts`：挂 `com.google.devtools.ksp` 插件；添加 room-runtime / room-ktx / room-compiler(ksp) / okhttp
- `AndroidManifest.xml`：`INTERNET` 权限（云端模型通道必需）

> ⚠️ 版本风险预案（AGP 9.3.1 内置 Kotlin，KSP 需匹配内置 Kotlin 版本）：
> 若 Gradle Sync 报 KSP/Kotlin 版本不匹配，在 `libs.versions.toml` 中把 `ksp` 版本改为与报错提示一致的 KSP 2.x 版本（格式如 `2.2.0-2.0.2`，可到 github.com/google/ksp/releases 查与内置 Kotlin 版本匹配的版本号），其余代码零改动。

### 1.2 数据层（Room，3 表）
| 表 | 对应 PPT 概念 | 关键字段 |
|---|---|---|
| `memory_items` | Memory Item Card 原子记忆卡 | memoId / content / title / category（封闭分类）/ tags / source / appId / policyLevel（0公开 1普通 2敏感）/ visibility / createdAt / updatedAt / expiresAt / confidence / conflictState / reuseCount / evidenceRaw / links(JSON) |
| `memory_logs` | 记忆调用日志三板块 | logType（COLLECT 传入 / RETRIEVE 检索 / INFER 推理）/ action / appId / memoIds / timestamp / source / contentSummary / tags / extra(JSON) |
| `registered_apps` | 多应用接入登记 | appId / appName / scope（READ/WRITE）/ createdAt |

### 1.3 核心服务
- **MemoryPipeline**：`collect`（净化 trim/长度校验）→ **SecurityGate**（敏感词栅栏：身份证/银行卡/密码/住址/手机号等 → 命中即 policyLevel=2 并记录命中词；LLM 敏感分类为增强通道，失败不阻断）→ **LLM 结构化抽取**（标题/分类/标签/实体/置信度，网络失败自动降级为原文入库并标记）→ **去重**（归一化文本 24h 内同源重复 → 拒绝并记日志）→ 入库 + 双日志（COLLECT + INFER）
- **MemoryRetriever（基础版）**：关键词召回 + 权限过滤（policyLevel≤申请方权限），每次调用留 RETRIEVE 日志（语义重排在阶段 2）
- **ModelProvider 双通道**：
  - `CloudModelProvider`：OpenAI 兼容 `POST {base}/chat/completions`（端点自动适配 `/v1` 变体），30s 超时，健壮 JSON 提取（容忍 markdown fence / 截断）
  - `LocalModelProvider`：扩展点存根——Android 上部署本地小模型**可行**（llama.cpp Android / MLC-LLM 跑 GGUF，需 NDK 与模型文件，演示环境暂不启用），接口签名与云端一致，后续替换即热插拔

### 1.4 控制台 V1
- 底部导航两页：**记忆库** / **调用日志**
- 记忆库：卡片列表（标题/内容截断/分类/标签/来源/时间/敏感级徽标/置信度）、FAB 添加记忆（多行输入+来源选择）、长按删除、工具栏菜单（装载示例数据 / 清空记忆库）
- 调用日志：TabLayout 三板块（传入/检索/推理），格式化字段展示（时间/来源/内容摘要/标签/动作）
- 示例数据：10 条记忆（偏好×3、项目×2、日程×2、画像×1、任务×1、关系×1）+ 3 个示例应用登记（记事本/对话助手/文件分类器）

### 1.5 文档
- `docs/架构设计说明.md`：模块架构图、数据模型、流水线时序、双通道模型设计、与 PPT 概念映射
- `docs/接口文档.md`：`memo_collect / get_memo` 接口初稿（系统 API 形态，阶段 3 落地到 vibe 应用）

### 1.6 测试点（用户在 Android Studio 手工验证）
1. Sync 通过（若 KSP 报错按 1.1 预案改版本）
2. 启动 → 工具栏"装载示例数据" → 记忆库出现 10 张记忆卡，日志出现批量"传入"流水
3. FAB 添加一条普通记忆（如"我周末喜欢去西湖跑步"）→ 卡片出现且带分类/标签/置信度，日志"传入"+"推理"各 +1 条（推理=LLM 抽取记录）
4. 断网添加一条记忆 → 正常入库（降级路径），日志标注"降级"
5. 添加含"身份证号 110101199001011234"的记忆 → 卡片标"敏感"徽标
6. 重复添加同一条 → 第二次被拒并记录日志
7. 日志页三板块切换正常

---

---

## 阶段 1 修复清单（2026-08-06 评审后，分支 `api-key-warning`）

评审发现的问题与修复：

| # | 问题 | 修复 |
|---|---|---|
| F1 | 顶部工具栏（两个 always 菜单项）在 Pixel 9 上超高、垃圾桶/工具栏无法点击 | 工具栏仅保留标题 + 汉堡；装载示例/清空/同步云端/模型设置/审计导出 全部移入**左侧导航抽屉**（DrawerLayout + NavigationView），修复后所有操作可点击 |
| F2 | 核心逻辑无 AI 参与，静默降级为硬编码引擎；日志 EXTRA 被隐藏，无法定位降级原因 | ① 修正模型端点（`BaseURL` 含 `/v1`）并轮换新 API Key；② 每次模型调用写入 `ModelDiagnostics`（成功/失败 + 精确原因：网络异常/HTTP 状态码/解析失败/超时）；③ 流水线捕获 `degradeReason` 写入 COLLECT + INFER 日志；④ 日志条目**点击展开显示 extra JSON**；⑤ 记忆库/抽屉头部实时展示"模型通道 + 最近调用结果" |
| F3 | 无联网/断网两种状态的路由反馈 | 新增 `NetworkMonitor`（ConnectivityManager → StateFlow）——在线/离线即时反馈到：记忆库顶部状态徽标、抽屉头部、云端树可达性；断网时云端树显示"不可达" |
| F4 | 需要 Local Tree / Cloud Tree 双库 + 云端内网树隔离（云端可拉取本地，本地不能 pull 云端） | 独立云端库 `osmemory_cloud.db`（模拟云端企业/个人库）；`TreeSyncManager` 作为 Network Gateway 做**单向**本地→云端拉取；敏感/保密记忆（policyLevel=2 或勾选保密）永不外发；本地树从云端读回被架构禁止；记忆库页双树 Tab 切换 + 每卡同步状态徽标（仅本地/待同步/已同步/同步失败/敏感不迁移/云端） |

## 阶段 2 详细清单（2026-08-07 本轮交付）

- **记忆画像三板块**（`ui/profile/ProfileFragment` + `core/profile/ProfileBuilder`）：用户画像 / 风格偏好 / 工作项目 + 遴选标签；LLM 从本地树聚合生成，离线/失败统计降级（最高频标签），降级原因显示并留 INFER + RETRIEVE 日志
- **语义检索**（`core/retrieval/SemanticReranker`）：`get_memo` 关键词召回 + LLM 语义重排，记忆库顶部搜索框落地；重排状态（ai/降级+原因）写入 RETRIEVE 日志
- **记忆修改流程**（`MemoryPipeline.update`）：先画像后改——点击卡片查看该记忆画像上下文（分类/标签/敏感级/置信度/时间），再编辑；保留 memoId/createdAt，重跑抽取，可勾选"保密不迁移云端"；留 COLLECT(update) + INFER 日志
- **模型设置页**（`ui/settings/ModelSettingsFragment`）：改 BaseURL/Model/API Key，测试连接（成功/失败+原因直显），保存即热插拔通道（`ModelManager.reset()`）
- **审计导出**（`data/AuditExporter`）：本地树 + 云端树 + 全部日志序列化为 JSON 审计快照，经系统文档创建器（SAF）导出

## 版本状态记录

- [x] **阶段 1 最小系统** — 2026-08-05 完成
- [x] **阶段 1 评审修复 + 阶段 2 控制台完整版/检索/画像** — 2026-08-07 完成（分支 `api-key-warning`，未提交，等待手动 commit）
- [x] **阶段 3 vibe 三应用 + 系统 API 封装** — 2026-08-07 完成（分支 `phase3-dev`）
- [ ] **阶段 4 进阶整合 + 演示打磨**

## 阶段 3 详细清单（2026-08-07）

- **系统 API 门面**（`phase3/api`）：三个逻辑应用自动登记与 scope 校验；提供
  `memoCollect / memoUpdate / memoDelete / getMemo / autoRecommend`；普通应用固定
  `policyMax=1`，只访问 Local Tree，DTO 与 Room Entity 解耦以便阶段 4 包装 Binder/AIDL。
- **记事本**（`phase3/notes`）：无标题文字记录的新增、编辑、保存、删除；保存后由用户选择
  是否立即关联记忆；已关联内容可显式更新，删除时可选择是否同时删除关联记忆。
- **对话问答**（`phase3/chat`）：简单 Chatbot + 单一记忆开关；开启后 `getMemo` 注入上下文，
  回答展示引用记忆；模型逐条提炼项目/会话记忆并立即写回记忆数据流，全局记忆暂不实现。
- **文件分类器**（`phase3/classifier`）：只提供无文件 I/O 的伪上传入口；保留家庭/工作/生活/旅行
  四个默认类别；模型扫描本地记忆标题、标签、正文摘要后动态追加开放类别。
- **安全配置**：API Key 从本机 `local.properties` 或 `OS_MEMORY_API_KEY` 注入，不写入源码/Git。
- 详细接口、行为与验收步骤见 `docs/阶段3系统API与三应用说明.md`。
