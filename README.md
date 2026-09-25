# 本地代码上下文索引与检索服务（Code Context Indexer）

一个仅依赖本地文件系统的 Spring Boot 服务：对指定本地代码目录建立可刷新的文本索引，
并在字符预算内按确定性规则组装检索上下文。**不依赖任何外部服务、网络或真实凭据。**

---

## 1. 快速开始

```bash
# 指定要索引的目录（也可在刷新时通过 root= 参数传入）
mvn spring-boot:run -Dspring-boot.run.arguments="--cci.root=/path/to/your/repo"

# 建立 / 刷新索引
curl -X POST "http://localhost:8080/api/index/refresh"
# 或不显式配置，刷新时指定：
curl -X POST "http://localhost:8080/api/index/refresh?root=/path/to/your/repo"

# 查看索引状态（世代、计数、拒绝明细）
curl "http://localhost:8080/api/index/status"

# 在 2000 字符预算内检索
curl "http://localhost:8080/api/index/search?q=refresh&budget=2000"

# 查看当前生效配置
curl "http://localhost:8080/api/index/config"
```

运行测试：

```bash
mvn test
```

---

## 2. HTTP 协议

| 方法 & 路径 | 参数 | 说明 |
|---|---|---|
| `POST /api/index/refresh` | `root`（可选） | 建立或刷新索引。`root` 缺省时使用 `cci.root`；两者都为空返回 `409`。 |
| `GET  /api/index/status` | — | 返回 `initialized`、`generation`、`indexedFileCount`、`rejectedCount` 及每条拒绝记录。 |
| `GET  /api/index/search` | `q`（必填）、`budget`（默认 2000） | 在预算内返回命中片段、命中行号、是否裁剪及裁剪依据。 |
| `GET  /api/index/config` | — | 返回当前生效的关键配置项。 |

`RefreshResult` 字段：`generation`、`root`、`reusedInFlight`、`added`、`updated`、
`deleted`、`rejected`、`indexedFileCount`、`rejectedCount`、`durationMillis`。

错误以 JSON 返回：`409`（单根冲突 / 缺少根目录 / 并发刷新了不同根）、
`400`（非法参数，如负预算）、`500`（根目录不存在、不可读、扫描 I/O 错误）。

---

## 3. 索引与刷新协议

### 3.1 索引内容

- 递归遍历根目录，跳过 `cci.exclude-dirs` 中的目录名（**配置策略**，不产生拒绝记录）。
- 仅接受 `cci.supported-extensions` 白名单内的**常规文本文件**，以 UTF-8 读取。
- 每个文件保存：相对路径、绝对路径、大小、最后修改时间、规范化全文 SHA-256、文本行。
  行尾统一规范化（CRLF/CR → LF）并去掉末尾空行，因此同一内容无论原始行尾如何，
  哈希与行列表完全一致。

### 3.2 文件变更的反映（无过期内容）

每次刷新执行一次完整扫描，与上一快照按规范化相对路径对比：

- **新增**：旧快照没有的相对路径 → `added`；
- **修改**：路径相同但 SHA-256 不同 → `updated`（mtime 仅作展示，是否变更以内容哈希为准）；
- **删除**：新扫描中消失的路径 → `deleted`；
- **重命名**：表现为"旧路径删除 + 新路径新增"，由整快照替换保证旧路径不残留。

新快照**整体原子发布**，不存在对旧文件的原地修改，因此不会残留过期内容、
不会出现重复条目或计数漂移。

### 3.3 拒绝原因（不静默跳过任何文件）

每个无法索引的文件都进入拒绝列表，原因可区分、细节可解释：

| 原因 | 触发条件 |
|---|---|
| `UNSUPPORTED_EXTENSION` | 扩展名不在白名单（疑似二进制/不支持类型），detail 含当前白名单 |
| `TOO_LARGE` | 文件字节数超过 `cci.max-file-bytes`，detail 含实际大小与上限 |
| `UNREADABLE` | 文件存在但进程无读权限（或安全管理器拒绝） |
| `READ_ERROR` | 读取过程发生 I/O 异常，detail 含异常类型与消息 |
| `NOT_REGULAR_FILE` | 符号链接、管道、设备文件等非常规文件（含指向目录的符号链接） |

排除目录属于配置策略，会在服务日志中列出但不计入拒绝。

### 3.4 并发刷新（单飞 + 幂等）

- 同一时刻**只有一个线程实际扫描**目录（single-flight）。
- 扫描进行中到达的同根刷新请求共享同一次扫描与同一份结果
  （`reusedInFlight=true` 语义；世代号相同），不重复扫描、不重复计数。
- 世代号 `generation` 单调递增，仅在单飞锁内 +1。
- 服务只绑定一个根目录：对其他根目录的刷新（含扫描进行中的并发请求）返回 `409`。
- 扫描失败时不发布新快照，旧索引保持可用，并在 `inFlight` 槽清空后允许重试。

---

## 4. 一致性边界

- **检索只读已发布快照**：`IndexSnapshot` 是不可变 record，由 `AtomicReference`
  原子持有。扫描在私有结果上进行，完成后一次性替换引用。
  因此任何检索读到的要么是旧的完整快照，要么是新的完整快照，
  **绝不会观察到刷新中途的半成品**（例如 generation 已递增但文件只进了一半）。
- 每次检索在开始时抓取一个快照引用，整次查询基于同一快照，结论自洽。
- 检索结果携带 `generation`，可判断结论基于哪一版索引。
- **边界之外**：索引不监听文件系统事件，变更只在下一次 `refresh` 后可见；
  快照对应"扫描时刻"的目录状态，扫描之后、发布之前发生的文件变更属于下一版。

---

## 5. 检索：打分、片段与预算

### 5.1 确定性打分

1. 查询按非字母/非数字字符分词并小写（Unicode `\p{L}\p{N}`，`Locale.ROOT`）；
2. 文件逐行分词，每个查询 term 在某行命中得 1 分（同一行对同一 term 只计一次）；
3. term 出现在文件路径名中额外 +2 分；
4. 命中按 **分数降序** 排序，平分时按 **相对路径字典序升序**，顺序稳定。

片段：每个命中行取前后 `cci.snippet-context-lines` 行作为窗口，
同一文件内重叠/相邻窗口合并；片段带 `path:from-to` 头与 1-based 命中行号列表。

### 5.2 预算裁剪规则（可复现、可解释）

- 片段全局按 `(分数降序, 路径升序, 起始行升序)` 排列；
- 预算以 **Unicode 码点**计数（避免多字节字符造成预算漂移），片段间计入分隔符；
- 按顺序贪心装入，**遇到第一个装不下的片段即停止**，其后所有片段一律省略；
- `truncated=true` 时 `truncationRule` 说明上述裁剪依据；
  若装不下的恰好是最后一个片段（此前全部已装入），则不算裁剪。
- 相同 `(索引世代, 查询, 预算)` 的输入永远得到逐字段一致的输出。

---

## 6. 配置项（前缀 `cci`）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `cci.root` | （空） | 索引根目录；为空时刷新必须带 `root` 参数。服务绑定后不可切换。 |
| `cci.supported-extensions` | `java,txt,md,properties,xml,yml,yaml,json,js,ts,css,html,sql,sh,csv` | 允许的文本扩展名白名单。 |
| `cci.max-file-bytes` | `1048576`（1 MiB） | 单文件大小上限，超出记 `TOO_LARGE`。 |
| `cci.exclude-dirs` | `.git,target,build,node_modules,.idea` | 遍历时跳过的目录名（策略性跳过，不记拒绝）。 |
| `cci.snippet-context-lines` | `2` | 命中行上下各取的行数。 |
| `cci.fragment-separator` | `\n---\n` | 片段分隔符，按码点计入预算。 |

另：`server.port=0` 表示启动时随机选择本地端口。

---

## 7. 代码结构

```
config/    IndexerProperties（cci.* 绑定）、IndexerConfig
model/     不可变记录：IndexSnapshot / IndexedFile / RefreshResult /
           IndexStatus / RejectedFile / RejectReason / SearchResult / SearchHit
scanner/   DirectoryScanner（遍历、读取、规范化、拒绝分类）、ScanOutcome
index/     InMemoryIndexStore（原子快照引用）
service/   IndexService（单飞刷新、差异、世代）、SearchService（打分、片段、预算）
web/       IndexController（HTTP 协议）、ApiExceptionHandler（稳定错误响应）
util/      TextTokenizer（确定性分词）、CharBuf（UTF-8 读取、码点计数）
```

## 8. 测试覆盖

- `IncrementalIndexTest`：新增 / 修改 / 删除 / 重命名 / 重复刷新幂等。
- `RejectionReasonTest`：扩展名、超限、符号链接、不可读（非 root 环境）四类拒绝原因可区分。
- `ConcurrentRefreshTest`：16 线程并发刷新只触发一次扫描、共享同一世代、计数不漂移。
- `ConcurrentSearchRefreshTest`：刷新发布前检索只见旧完整快照，发布后新世代始终完整。
- `ContextBudgetTest`：确定性排序、上下文窗口、预算边界、裁剪依据、同输入可复现、改文件后结论更新。
- `IndexControllerIntegrationTest`：HTTP 全生命周期、拒绝原因透传、单根冲突 409。

测试通过 `TestJudge` 在日志中统一打印**受影响输入**与**判定依据**。
