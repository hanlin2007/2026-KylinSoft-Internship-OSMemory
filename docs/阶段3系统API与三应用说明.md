# 阶段 3：系统 API 与三应用说明

## 1. 工程边界

阶段 3 继续遵循 Kotlin + XML View、单 `:app` 模块的工程基线。记事本、记忆问答、
文件分类器分别以独立 Launcher Activity 呈现，但仍运行在 OS Memory APK 的同一进程中，
通过 `phase3/api/MemoryApiService` 调用本地树。阶段 4 再把同一 DTO/调用语义包装为
Binder/AIDL，不在本阶段提前引入跨进程传输。

三个逻辑应用固定登记如下：

| 应用 | appId | source | scope |
|---|---|---|---|
| 记事本 | `app_notes` | `notes` | `WRITE` |
| 记忆问答 | `app_chat` | `chat` | `READ_WRITE` |
| 文件分类器 | `app_files` | `files` | `READ_WRITE` |

所有读取只访问 Local Tree，普通应用的 `policyMax` 固定为 1；三个 UI 不直接访问云树、
Network Gateway 或 Room DAO。

## 2. MemoryService API

入口：

```kotlin
val client = MemoryApiService.client(context, Phase3App.CHAT)
```

公开调用语义：

```kotlin
suspend fun register()
suspend fun memoCollect(content: String): MemoCollectResult
suspend fun memoUpdate(memoId: String, content: String, forceSecret: Boolean? = null): MemoUpdateResult
suspend fun memoDelete(memoId: String): MemoDeleteResult
suspend fun getMemo(query: String, limit: Int = 10, semantic: Boolean = true): List<MemoryMemo>
suspend fun autoRecommend(scene: String, limit: Int = 60): List<MemoryMemo>
```

- `register` 幂等执行；其他方法首次调用前也会自动登记。
- `memoCollect` 走净化、安全门控、LLM 抽取、24 小时同源去重、Local Tree 入库以及
  COLLECT/INFER 双日志。
- `memoUpdate`、`memoDelete` 只允许操作当前应用自己创建的记忆。
- `getMemo` 执行权限过滤、关键词召回与可选语义重排，并产生 RETRIEVE 日志。
- `autoRecommend` 为场景代理编译近期本地记忆上下文并产生 RETRIEVE 日志；它不返回敏感
  记忆，不访问 Cloud Tree。
- `MemoryMemo` 是与 Room Entity 解耦的 DTO，阶段 4 可直接迁移到 IPC 序列化层。

## 3. 三应用行为

### 3.1 记事本

- 只有无标题文本记录，使用应用私有 SharedPreferences/JSON 保存，不改 OS Memory 数据库结构。
- 主页面只展示记录列表；新建或点击记录后进入独立编辑页，支持保存、删除。
- 新记录保存后询问“是否关联到记忆”；确认后立即调用 `memoCollect`，无额外同步按钮。
- 已关联记录再次保存时可同步调用 `memoUpdate`。
- 删除已关联记录时明确让用户选择“只删记录”或“同时删除关联记忆”，不静默级联。

### 3.2 记忆问答

- 设置页只提供“启用 OS Memory”开关，并持久化用户选择。
- 关闭时仅进行普通模型问答，不读写 OS Memory。
- 开启时先用 `getMemo(..., semantic = true)` 检索本地记忆，把命中内容真实注入模型上下文；
  回答区域同时展示本轮引用的标题/memoId，便于验收。
- 暂不实现全局记忆。模型每轮可生成一条项目/会话原子记忆；非空项会逐条保存在本应用的
  项目/会话记忆列表，并立即逐条调用 `memoCollect` 进入系统记忆数据流。

### 3.3 文件分类器

- 默认类别固定为“家庭、工作、生活、旅行”。这些是分类器自己的开放类别，不修改
  `MemoryItem.category` 的封闭枚举。
- “伪上传”只展示接口反馈，不申请文件权限、不读取或上传任何真实文件。
- “记忆扫描”通过 `autoRecommend` 取得本地普通记忆的标题、标签和正文摘要，再调用统一
  `ModelProvider` 生成 JSON 类别数组；清洗、去重后追加并持久化，不写死动态类别。
- 无记忆、模型失败或解析失败时保留默认类别，并向用户显示原因。

## 4. 模型密钥

API Key 不写入源码或 Git。开发机可任选一种方式提供：

```properties
# local.properties（已被 .gitignore 排除）
osmemory.apiKey=YOUR_KEY
```

或设置环境变量 `OS_MEMORY_API_KEY`。构建时生成的 `BuildConfig.PHASE3_API_KEY` 只在用户尚未
通过控制台设置模型 Key 时安装一次，不覆盖用户已有配置。BaseURL/Model 仍完全复用现有
`ModelConfig` 和 `ModelManager`，便于与阶段 2 的双模型网关修复合并。

## 5. 手工验收

1. 安装 Debug APK 后，桌面出现控制台以及三个阶段 3 独立入口。
2. 记事本新建文本并保存；选择关联后，控制台 Local Tree 立即出现来源为 `notes` 的记忆。
   编辑并选择同步更新后内容变化；删除时分别验证“仅删记录”和“同时删除关联记忆”。
3. 在控制台先装载示例记忆。打开记忆问答并开启记忆，提问与示例记忆相关的问题；回答下方
   应显示引用记忆，调用日志出现 RETRIEVE/INFER。模型产生的项目/会话记忆应逐条出现在列表，
   同时出现在控制台 Local Tree。
4. 打开文件分类器，确认初始只有四个默认类别；点击伪上传，确认提示明确说明未读取/未上传。
   点击记忆扫描后，应出现由当前本地记忆动态产生的新类别，调用日志出现
   `auto_recommend` 与 `file_category_scan`。
5. 添加敏感记忆后再次问答/扫描，确认阶段 3 应用不会读取 `policyLevel=2` 内容。

自动化测试不得调用真实公网模型，模型响应解析与类别清洗使用确定性 JVM 单元测试覆盖。
