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

#### rootDir 参数校验（失败不损坏旧索引）

刷新前对 `rootDir` 做校验，三种非法输入分别返回 **HTTP 400** 且原因可区分：

| `reason` | 含义 |
| --- | --- |
| `NOT_FOUND` | 路径不存在 |
| `NOT_A_DIRECTORY` | 路径存在但不是目录 |
| `UNREADABLE` | 是目录但不可读 |

响应体示例：`{"error":"INVALID_ROOT_DIR","reason":"NOT_FOUND","rootDir":"...","message":"..."}`。

**失败保护**：校验或扫描失败时抛错返回，已发布快照保持原样 ——
失败前后的检索结果继续可用，快照版本号不推进；换回正确目录再次刷新即可
恢复正常（版本号照常 +1）。

### 索引状态

```
GET /api/index/status
```

返回当前快照版本、根目录、构建时间、已索引/被拒绝计数及拒绝明细。

### 检索与上下文组装

```
GET /api/search?q=<查询词>&budget=<字符预算>
```

- **切词（支持中文与中英混排）**：查询先转小写；连续的英文字母/数字/下划线
  构成一个词元；连续的 CJK 表意文字（中文）拆为**全部单字词元 + 全部相邻
  二字词元**；其余字符视为分隔符。因此纯中文、纯英文、中英混排查询都能
  命中确实包含对应内容的片段。
- 片段得分 = 各词元在片段内容（小写）中出现次数之和。
- 命中排序：得分降序，并列按 `(path, chunkIndex)` 字典序 —— 完全确定，
  相同快照 + 相同查询重复执行，结果与排序逐条一致。
- 预算内按排名**贪心装入**片段（片段间分隔符 `\n---\n` 计入预算）；
  装不下的命中记入 `trimmed` 并附裁剪依据（需要字符数 vs 剩余预算）。
- 相同快照 + 相同查询 + 相同预算 ⇒ 结果逐字节一致。

#### 命中截断说明

当命中片段数超过 `indexer.max-results` 时，响应明确给出截断信息：

| 字段 | 含义 |
| --- | --- |
| `totalHits` | 实际命中的片段总数（截断前） |
| `hitLimit` | 单次返回上限（即 `indexer.max-results`） |
| `truncatedByHitLimit` | 是否因超过上限被截断 |
| `retentionRule` | 截断时保留/丢弃所依据的规则说明 |

截断规则：命中按排名（得分降序，并列按 `path`、`chunkIndex` 字典序）
**保留前 `maxResults` 条**，丢弃排名靠后的命中；排名为纯函数，
重复查询保留集合一致。

## 一致性边界

| 保证 | 说明 |
| --- | --- |
| 快照原子发布 | 检索只会看到旧快照或新快照**整体**，绝不观察到刷新中间状态 |
| 串行刷新 | 并发/重复刷新排队执行，版本号严格 +1，计数由快照派生、无独立计数器 |
| 确定性 | 扫描按路径字典序、切分规则固定、评分与排序为纯函数 |
| 无静默跳过 | 每个非常规/不可读/不支持/超限/二进制文件都记入 `rejected` 并附原因 |
| 失败不损坏索引 | 刷新参数非法或扫描失败时抛错，已发布快照原样保留，检索持续可用 |

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

- 接口路径、请求参数、刷新/状态响应结构保持不变；
- 检索响应**新增** `totalHits / hitLimit / truncatedByHitLimit / retentionRule`
  字段，原有 `query / budgetChars / usedChars / included / trimmed` 字段语义不变；
- 英文查询的切词与评分规则与之前一致（仅新增中文词元），既有英文检索结论不受影响；
- 之前对非法 `rootDir` 返回的笼统 500 改为可区分的 400（`INVALID_ROOT_DIR`），
  调用方如依赖 500 状态码需相应调整。

## 本地复现与验证

```bash
# 启动服务
mvn spring-boot:run

# 1. 中文 / 中英混排检索
mkdir -p /tmp/demo && echo '// 用户登录的鉴权逻辑' > /tmp/demo/注释.java
curl -X POST 'http://localhost:8080/api/index/refresh?rootDir=/tmp/demo'
curl 'http://localhost:8080/api/search?q=鉴权&budget=2000'        # 纯中文
curl 'http://localhost:8080/api/search?q=login+鉴权&budget=2000'  # 中英混排

# 2. 命中截断说明：构造超过 indexer.max-results 的命中后观察
#    响应中的 totalHits / hitLimit / truncatedByHitLimit / retentionRule

# 3. 非法目录刷新（三种原因可区分，且旧索引不受影响）
curl -X POST 'http://localhost:8080/api/index/refresh?rootDir=/tmp/不存在'
#   -> 400 {"error":"INVALID_ROOT_DIR","reason":"NOT_FOUND",...}
curl 'http://localhost:8080/api/search?q=鉴权'   # 旧结果仍可用
curl -X POST 'http://localhost:8080/api/index/refresh?rootDir=/tmp/demo'  # 恢复
```

## 测试

```
mvn test
```

覆盖：增量变更（增/改/删/重命名）、拒绝原因可区分、并发刷新幂等与
检索不观察中间状态、上下文组装的确定性与裁剪可解释性、
中文/中英混排查询命中与重复查询一致性、命中超限时的截断说明与保留规则、
非法 rootDir 的可区分 400 与失败后旧索引可用/可恢复、
接口层刷新→状态→检索串联流程。
测试日志打印受影响输入与判定依据。
