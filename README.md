# 本地代码索引与上下文检索服务

基于 Spring Boot 的纯本地服务：对指定代码目录建立/刷新索引，并在给定长度预算内
组装可复现的上下文片段。仅使用本地文件系统，不依赖任何外部服务或网络。

## 索引与刷新协议</h2>

### 建立 / 刷新索引

```
POST /api/index/refresh?rootDir=/path/to/repo
```

- 每次刷新对 `rootDir` 做一次**全量扫描**，基于磁盘当前状态完整构建新快照，
  构建完成后**原子替换**旧快照。刷新是磁盘状态的纯函数，天然幂等：
  重复刷新、并发刷新不会产生重复条目或计数漂移。
- 并发刷新在公平锁下**串行执行**，每次刷新使快照版本号恰好 +1。
- 返回 `RefreshReport`，相对上一快照给出可解释差异：
  `added / modified / removed / unchanged`（按内容 SHA-256 判定修改）、
  `indexedTotal / rejectedTotal` 及全部被拒绝文件明细。
- 新增、修改、删除、重命名（= 删除旧路径 + 新增新路径）都会正确反映，
  不残留过期内容。

#### rootDir 参数校验（新增）

刷新前先校验 `rootDir`，非法输入返回 **HTTP 400**，响应体携带
可区分的 `error` 码、`message` 说明与出错的 `rootDir`：

| `error` | 含义 |
| --- | --- |
| `NOT_EXISTS` | 路径不存在 |
| `NOT_A_DIRECTORY` | 路径存在但不是目录 |
| `UNREADABLE` | 是目录但不可读 |

**失败不影响已发布索引**：校验或扫描失败时旧快照原样保留，
版本号不变，检索继续返回旧结果；换回正确目录再次刷新即可恢复正常
（版本号在旧版本上 +1）。

### 索引状态

```
GET /api/index/status
```

返回当前快照版本、根目录、构建时间、已索引/被拒绝计数及拒绝明细。

### 检索与上下文组装

```
GET /api/search?q=<查询词>&budget=<字符预算>
```

- **分词（新增，支持中文与中英混排）**：
  - 连续拉丁字母/数字/下划线切为一个词并转小写；
  - 连续 CJK 字符切为中文段：单字直接成词，多字拆为相邻**二字组**（bigram）；
  - 其余字符（空白、标点）仅作分隔。
  中文、英文及中英混合查询都能命中确实包含对应内容的片段。
- 片段得分 = 各词在片段内容中出现次数之和（中文二字组按子串匹配）。
- 命中排序：得分降序，并列按 `(path, chunkIndex)` 字典序 —— 完全确定。
- **命中数截断说明（新增）**：响应中
  - `totalHits`：本次查询实际命中的片段总数（截断前）；
  - `returnedHits`：本次实际返回的命中条数；
  - `hitLimit`：单次返回上限（`indexer.max-results`）；
  - `truncatedByHitLimit`：是否因超限被截断；
  - `hitLimitRule`：截断规则说明 —— 按排名（得分降序，并列按
    `path/chunkIndex` 字典序）**保留前 `hitLimit` 条**，丢弃排名靠后的其余命中。
  保留下来的永远是排名最前的部分，重复查询结论一致。
- 预算内按排名**贪心装入**片段（片段间分隔符 `\n---\n` 计入预算）；
  装不下的命中记入 `trimmed` 并附裁剪依据（需要字符数 vs 剩余预算）。
- 相同快照 + 相同查询 + 相同预算 ⇒ 结果逐字节一致。

## 一致性边界

| 保证 | 说明 |
| --- | --- |
| 快照原子发布 | 检索只会看到旧快照或新快照**整体**，绝不观察到刷新中间状态 |
| 串行刷新 | 并发/重复刷新排队执行，版本号严格 +1，计数由快照派生、无独立计数器 |
| 失败不毁索引 | rootDir 校验或扫描失败时旧快照原样保留，检索继续可用 |
| 确定性 | 扫描按路径字典序、切分规则固定、分词/评分/排序/截断均为纯函数 |
| 无静默跳过 | 每个非常规/不可读/不支持/超限/二进制文件都记入 `rejected` 并附原因 |

边界说明：刷新结果反映的是**扫描瞬间**的磁盘状态；扫描期间正在写入的
文件以下一次刷新为准。检索结果的一致性以 `snapshotVersion` 标识。

## 拒绝原因（RejectReason）

| 原因 | 含义 |
| --- | --- |
| `UNREADABLE` | 文件不可读或读取失败 |
| `UNSUPPORTED_TYPE` | 扩展名不在支持列表 |
| `TOO_LARGE` | 超过 `indexer.max-file-size-bytes` |
| `BINARY` | 含 NUL 字节或非法 UTF-8 序列 |
| `NOT_REGULAR_FILE` | 非常规文件（如 FIFO、socket） |

## 配置项（application.properties，前缀 `indexer`）

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `indexer.supported-extensions` | java, xml, properties, yml, yaml, md, txt, json, sql, gradle | 支持索引的扩展名 |
| `indexer.max-file-size-bytes` | 1000000 | 单文件大小上限 |
| `indexer.chunk-max-lines` | 40 | 单片段最大行数 |
| `indexer.chunk-max-chars` | 2000 | 单片段最大字符数（超长行硬切） |
| `indexer.max-results` | 50 | 单次检索最大命中数 |

## 兼容性说明

- 既有接口路径、请求参数与响应字段**全部保留**，仅在 `/api/search`
  响应中**新增** `totalHits / returnedHits / hitLimit /
  truncatedByHitLimit / hitLimitRule` 字段，旧调用方不受影响。
- 英文查询的分词与评分行为与之前一致（拉丁词小写子串计数）；
  中文查询此前无法分词（命中为空），现在按二字组命中，属于行为修复。
- `/api/index/refresh` 对非法 `rootDir` 由此前的 500 改为 **400 +
  可区分 error 码**；失败请求不再影响已发布索引。

## 测试

```
mvn test
```

覆盖：增量变更（增/改/删/重命名）、拒绝原因可区分、并发刷新幂等与
检索不观察中间状态、上下文组装的确定性与裁剪可解释性；
新增覆盖：中文/中英混合查询命中与重复执行一致性
（`ChineseSearchTest`）、命中超限时的截断说明与保留规则
（`SearchTruncationTest`）、刷新失败原因可区分且旧索引可用、
恢复刷新（`RefreshFailurePreservesIndexTest`）、接口层
刷新→状态→检索→失败→恢复端到端链路（`ApiEndToEndTest`）。
测试日志打印受影响输入与判定依据。

## 本地复现与验证

```bash
# 启动服务
mvn spring-boot:run

# 1. 中文检索：对含中文注释的仓库建索引后用中文/中英混合查询
curl -X POST 'http://localhost:8080/api/index/refresh?rootDir=/path/to/repo'
curl 'http://localhost:8080/api/search?q=订单&budget=4000'
curl 'http://localhost:8080/api/search?q=登录%20login&budget=4000'

# 2. 截断说明：用较小的 indexer.max-results 启动（如 --indexer.max-results=3），
#    查询响应中观察 totalHits / returnedHits / truncatedByHitLimit / hitLimitRule

# 3. 非法目录：分别得到 NOT_EXISTS / NOT_A_DIRECTORY / UNREADABLE 的 400 响应，
#    之后旧索引仍可检索，换回正确目录刷新恢复正常
curl -X POST 'http://localhost:8080/api/index/refresh?rootDir=/no/such/dir'
curl 'http://localhost:8080/api/index/status'
```
