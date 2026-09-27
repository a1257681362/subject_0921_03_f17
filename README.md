# EVOPS 基础工作区

这是 springboot的骨架项目。

技术栈：Java 8、Spring Boot 2.7、MyBatis-Plus、H2 本地文件数据库、Shiro、Thymeleaf。

> 内部审计整改（auth-1）：场馆后台登录已改为 **JWT 双令牌**，Shiro 表单登录不再用于 `/api/**`。

启动前准备：

1. 执行 `mvn -q -DskipTests compile` 验证骨架构建。
2. 执行 `mvn spring-boot:run` 启动应用；**Flyway 在启动时自动按版本执行 `src/main/resources/db/migration/V*.sql`**（详见下文「Schema 变更管理」）。
3. H2 数据文件默认写入工作区 `data/evops`。如需修改路径，编辑 `application.yml` 中的 `jdbc:h2:file:` 连接串。

## Schema 变更管理（Flyway，2026-09 接管）

表结构不再靠各环境手工执行 SQL，统一由 Flyway 管版本：

- 脚本目录 `src/main/resources/db/migration`，命名 `V<版本号>__<描述>.sql`，启动时按版本号顺序自动执行，结果记入 `flyway_schema_history`（变更历史随时可查：`SELECT version,description,type,success,installed_on FROM flyway_schema_history`）。
- **新环境**：空库从 `V1__baseline_schema.sql`（现有全量快照）起顺序执行到最新版本，一键建到最新。
- **现有环境（旧库）**：配置了 `baseline-on-migrate: true` + `baseline-version: 1`，首次启动把 V1 记为基线（不重放全量脚本、不动存量数据），只执行基线之后的增量；之后各环境版本保持一致。
- 已执行的版本脚本**禁止修改**（启动校验会拦）；任何变更只能新增 `V<n+1>__xxx.sql`。
- **建到一半失败可直接重启续建（失败自愈）**：新版本脚本若在某条语句中途失败，H2 上已执行的 DDL 不会回滚（半成品表残留），且 `flyway_schema_history` 会留一条 `success=FALSE` 记录，直接重跑会被 *Detected failed migration* 拦在 validate 阶段。`FailedMigrationRecovery`（挂在 `FlywayMigrationStrategy`，正式 migrate 前执行）会**只删除失败行**，随后由全幂等脚本（DDL 均 `IF NOT EXISTS`、DML 均 `MERGE`/`NOT EXISTS`）把半成品库安全收敛到最新——无需人工 repair 或删表。**红线**：只删 `success=FALSE` 的失败行，成功版本（含 BASELINE）一行不动、checksum 一字节不改；脚本本身仍有错时会在同一语句再次失败，自愈不掩盖真实缺陷。
- 当前版本：V1 基线（base/ticket/auth/checkin/agent 全部现有表）、V2（`t_refresh_token.expires_at` 夜间清理索引）、V3（演出票附件 `t_ticket_attachment` 元数据表与按票号索引）、V4（检票通知：模板/收件人/批次/逐通道发送记录/站内信箱）、V5（检票数据脱敏：规则版本表与明文查看留痕表，初始规则版本种子并把场务样本账号角色细化为 STAGE_STAFF）、V6（一人多岗 RBAC：角色/用户多岗/菜单树/角色授权/越权审计五张表，角色并集鉴权与动态菜单，存量账号回填，新增兼任样板账号 multi_ops01）。

**新旧逻辑并跑与切旧（观察期两天）**：接管初期保留旧的 `schema.sql`（语句全部幂等，结构已在则空跑），与 Flyway 同时工作；每个 Flyway 增量向旧台账 `t_evops_schema_version` 双写一行 `flyway-n`。每次启动 `SchemaHistoryReconciler` 自动核对两套台账（基线标记、逐版本双写、增量物理对象），一致时打印「Schema 台账对账一致」，不一致打 ERROR（`evops.schema.fail-on-mismatch=true` 可强卡启动）。两天数字对得上后，将 `spring.sql.init.mode` 置 `never`（环境变量 `SPRING_SQL_INIT_MODE=never`）即完成切旧；稳定后可关闭对账（`evops.schema.reconcile-enabled=false`）。

> `..\..\docs\schema\evops.sql` 交付副本现对应 `db/migration/V1__baseline_schema.sql` 的基线快照，后续增量以 `V*.sql` 为准。

## 场馆票务底账（ticket-1）

统一前缀 `/api/ticket`，返回结构统一为 `ApiResponse { success, message, data }`。

- 写请求请求头：`X-Operator-Id`（操作人，缺省 system）、`X-Request-Id`（请求号，写接口必传，作为幂等键；同号重复请求返回首次结果快照）。
- 写请求体可带 `bizTime`（业务时间，缺省取服务器时间）；每条业务记录均落库 operator / requestId / bizTime / version。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ticket/seats` | 座位建档（场馆/区域/排/座号） |
| GET | `/api/ticket/seats`、`/api/ticket/seats/{id}` | 座位分页/详情 |
| DELETE | `/api/ticket/seats/{id}` | 座位删除（只打逻辑删除标记，可按同位置重建） |
| POST | `/api/ticket/sessions` | 演出场次建档（sessionCode 为票号短码） |
| GET | `/api/ticket/sessions`、`/api/ticket/sessions/{id}` | 场次分页/详情 |
| POST | `/api/ticket/orders` | 售票单+票明细建档（主从同事务，不允许半截落库） |
| GET | `/api/ticket/orders/{id}` | 售票单详情（含票列表） |
| POST | `/api/ticket/orders/{id}/void` | 整单作废（反向记录，占用全部释放） |
| GET | `/api/ticket/tickets/{ticketNo}` | 按票号检索（含场次、座位、追溯流水） |
| GET | `/api/ticket/tickets?ticketNo=&sessionId=&status=&orderNo=` | 票分页检索 |
| POST | `/api/ticket/tickets/{ticketNo}/check` | 检票 SOLD→CHECKED |
| POST | `/api/ticket/tickets/{ticketNo}/refund` | 退票 SOLD→REFUNDED，释放座次 |
| POST | `/api/ticket/tickets/{ticketNo}/void` | 作废 SOLD/CHECKED→VOID，释放座次 |

要点：

- 同一座位按场次独立售卖：有效占用唯一约束在 `t_session_seat_occupy(session_id, seat_id)`；退票/作废物理删除占用行即释放，可同场次再售；历史票与流水原样保留。
- 作废/退票走 `t_ticket_status_flow` 反向记录（reverse_flag=1，origin_record_id 指向 ISSUE 流水），构成追溯链。
- 票状态机：SOLD→CHECKED（检票）、SOLD→REFUNDED（退票）、SOLD/CHECKED→VOID（作废）；非法流转返回 success=false。
- 票号系统生成：`T + 场次短码 + 10 位顺序号`，每场次计数行加锁分配，票号全局唯一。
- 并发控制：状态/主数据更新走 version 乐观锁；5 路同 requestId 并发只落 1 条记录、6 路抢同一座位恰好 1 单成功。

## 检票排期自动化（checkin-1）

把检票安排从人肉拼图改为区间排期。预约按**半开区间** `[startTime, endTime)` 存储，同一座位：

- **区间重叠即冲突**；
- **相邻预约首尾间隔不足 20 分钟也冲突**（边界恰好 20 分钟放行）；
- **取消即释放**：置 CANCELLED 后该区间立即不再参与冲突，可马上再约（历史行保留追溯）；
- 可查询每个座位在任意区间内的有效占用；
- 冲突时响应体直接带回撞上的预约与**推荐空档**，场务可改约。

并发写先对座位行 `SELECT … FOR UPDATE` 加锁按座位串行：5 路并发抢同一座位同一时段恰好 1 条成功；不同座位互不阻塞。写接口沿用 `X-Operator-Id` / `X-Request-Id` / `bizTime` 与幂等模板，**不改动既有票务契约**，仅新增端点（前缀 `/api/ticket/checkin`）。冲突不走 HTTP 错误码：`200 + success=false`，`data` 为冲突详情。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ticket/checkin/appointments` | 预约检票档口（seatId/startTime/endTime，visitorName/contact 可选） |
| POST | `/api/ticket/checkin/appointments/{id}/cancel` | 取消预约，立即释放，可再约（body 可选 reason/bizTime） |
| GET | `/api/ticket/checkin/appointments/{id}` | 预约详情 |
| GET | `/api/ticket/checkin/appointments` | 预约明细分页（seatId/status/from/to 可选） |
| GET | `/api/ticket/checkin/seats/{seatId}/occupancy?start=&end=` | 查询某座位区间内的有效占用 |
| GET | `/api/ticket/checkin/seats/{seatId}/free-slots?wantedStart=&wantedEnd=&durationMinutes=` | 空档推荐（3 天窗口、就近返回最多 5 个，时长缺省取目标区间） |

冲突响应示例（`success=false`，data 摘要）：

```json
{
  "success": false,
  "message": "该座位所选时段已被预约或与相邻预约间隔不足20分钟",
  "data": {
    "seatId": 1, "bufferMinutes": 20,
    "requestedStart": "2026-09-25T10:15:00", "requestedEnd": "2026-09-25T10:45:00",
    "conflicts": [ { "id": 1, "startTime": "2026-09-25T10:00:00", "endTime": "2026-09-25T10:30:00" } ],
    "suggestedSlots": [
      { "startTime": "2026-09-25T10:50:00", "endTime": "2026-09-25T11:20:00" }
    ]
  }
}
```

要点：

- 数据落 `t_checkin_appointment`，状态 `ACTIVE/CANCELLED`；预约号 `APT + 时间戳 + 4 位随机`，与审计字段（operator/requestId/bizTime/version）口径一致。
- 冲突判定为纯区间运算：既有 `[a,b)` 与新约 `[s,e)` 冲突当且仅当 `s < b+20m 且 a < e+20m`（半开区间，端点相接不算重叠）。
- 对外契约零改动，联调由场务牵头按上表端点确认。

## 代理售票平台 XML 报文对接（agent-1）

场务多次提出、此前卡在平台排期的代理售票平台对接，按平台只认 XML 报文的约束落实。用 **Jackson XML（jackson-dataformat-xml + Woodstox）** 做对象 ⇄ 报文双向映射，字段命名与层级严格按平台规范；**收发原文一律留档、按票号归档可回查**；出站失败**指数退避自动重试**；入站坏报文/不合规/业务失败**进异常清单**。

### 报文规范

根元素带平台命名空间 `http://schema.evops.com/ticket/v1`，UTF-8 编码、带 XML 声明；时间统一 `yyyy-MM-dd HH:mm:ss`（入站兼容 ISO-8601）。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<agentMessage xmlns="http://schema.evops.com/ticket/v1">
  <header>
    <messageId>MSG20260923193000A1B2C3D4</messageId>
    <messageType>TICKET_ISSUE</messageType>   <!-- TICKET_ISSUE/CHECK/REFUND/VOID -->
    <timestamp>2026-09-23 19:30:00</timestamp>
    <source>VENUE</source><target>AGENT</target>
  </header>
  <body>
    <ticket>
      <ticketNo>TSH002000000001</ticketNo>
      <orderNo>SO20260923190000A1B2</orderNo>
      <status>SOLD</status>
      <price>180.00</price>
      <operator>box_office01</operator>
      <bizTime>2026-09-23 19:30:00</bizTime>
      <session><showName>天鹅湖·芭蕾经典</showName><sessionCode>SH002</sessionCode>
               <showTime>2026-10-01 19:30:00</showTime><venue>滨江大剧院</venue></session>
      <seat><area>一层池座</area><rowNo>A</rowNo><seatNo>08</seatNo>
    </ticket>
  </body>
</agentMessage>
```

平台应答为 `<agentAck>`：`result.code` 取 `SUCCESS / DUPLICATE / REJECTED`，`correlationId` 回填原报文 `messageId`。

### 端点

| 方向 | 方法 | 路径 | 访问 | 说明 |
|---|---|---|---|---|
| 入站 | POST | `/api/agent/v1/messages` | 匿名（平台服务器直投） | 收 `application/xml`，回 ACK XML；HTTP 恒 200，结果看 ACK code |
| 管理 | GET | `/api/ticket/agent/messages` | 访问令牌 | 报文分页（direction/messageType/status/ticketNo 过滤） |
| 管理 | GET | `/api/ticket/agent/messages/{id}` | 访问令牌 | 报文详情（含收发原文） |
| 管理 | GET | `/api/ticket/agent/tickets/{ticketNo}/messages` | 访问令牌 | **按票号归档回查**：该票全部收发报文原文 |
| 管理 | POST | `/api/ticket/agent/messages/{id}/retry` | 访问令牌 | 手动重发失败/待发出站报文（原 messageId，平台幂等） |
| 管理 | POST | `/api/ticket/agent/tickets/{ticketNo}/push` | 访问令牌 | 按票补推（依票当前状态生成新报文，新 messageId） |
| 管理 | GET | `/api/ticket/agent/errors` | 访问令牌 | 异常清单分页（默认只看 OPEN，可按 errorType/ticketNo 过滤） |
| 管理 | GET | `/api/ticket/agent/errors/{id}`、`/errors/{id}/payload` | 访问令牌 | 异常详情 / 关联原始报文（坏 XML 原样回查） |
| 管理 | POST | `/api/ticket/agent/errors/{id}/resolve` | 访问令牌 | 标记 RESOLVED / IGNORED |

### 关键机制

- **双向映射**：`XmlMessageCodec` 用独立 `XmlMapper`（与后台 JSON 的 ObjectMapper 隔离）；`@JacksonXmlProperty` 固化字段名与 header/body/ticket/session/seat 层级；未知字段容错（前向兼容），必填项在业务层显式校验。
- **收发留档**：落 `t_agent_message`（含 payload 原文、messageId、方向、类型、票号、订单号、场次短码、HTTP 状态、重试计数、最后错误）；索引用 `(ticket_no, id)` 支撑按票号回查。
- **出站时机**：票状态变更（出票/检票/退票/作废）在**所在事务提交后**（`AFTER_COMMIT`）才通知平台——回滚不通知；出站失败不反噬票务主流程，报文已落 PENDING 由定时任务补发。票源在场馆，出站含全部四类；入站仅受理平台侧的 CHECK/REFUND/VOID 状态同步。
- **防回声**：入站报文驱动的票状态变更带线程级来源标记，提交后不再回推平台，避免把平台发起的变更又通知回去。
- **重试**：每分钟扫描到期 `PENDING/RETRYING` 出站报文（`AgentMessageRetryJob`），指数退避（基数 30s，封顶 30min），默认最多 5 次；网络异常与 HTTP 非 2xx 均重试，**重试耗尽置 FAILED** 等待人工 `/retry`；平台回 2xx 但 ACK=`REJECTED` 视为终态拒收（不做无意义重试）。
- **入站幂等**：以平台 `messageId` 为幂等键（库内唯一约束兜底并发重复）；重复投递回 `DUPLICATE`，不重复处理。
- **异常清单**：落 `t_agent_parse_error`，分 `PARSE_ERROR`（结构坏，仍兜底抽取 messageId/ticketNo、原文保留）/ `VALIDATION_ERROR`（缺必填、类型非法）/ `BIZ_ERROR`（票不存在、非法状态流转等），可标记处理。
- 入站票状态同步**复用场馆既有票状态机**（SOLD→CHECKED / SOLD→REFUNDED / SOLD·CHECKED→VOID），非法流转与乐观锁冲突按业务失败进异常清单，不改底账。

### 配置（`evops.agent`）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `enabled` / `EVOPS_AGENT_ENABLED` | true | 总开关；false 时业务仅落 PENDING 归档不投递，排期就绪后打开补发 |
| `endpoint` / `EVOPS_AGENT_ENDPOINT` | `http://localhost:8080/agent-stub/ticket` | 平台收票地址 |
| `venue-code` | VENUE | 场馆在平台侧的身份标识 |
| `connect-timeout-ms` / `read-timeout-ms` | 3000 / 5000 | HTTP 超时 |
| `max-retry` | 5 | 单条报文最大重试次数（不含首发） |
| `base-backoff-seconds` / `max-backoff-seconds` | 30 / 1800 | 退避基数与上限 |
| `retry-batch-size` | 100 | 定时重试每批上限 |

## 演出票附件与客服整批 ZIP 下载（ticket-2）

客服支持整批下载演出票附件：ZIP 打包、**单包最多 50 份票号**、中文文件名不乱码、包内带清单文件列出对应票号、**流式生成不占内存**。附件二进制存服务端受管目录，元数据落 `t_ticket_attachment`（Flyway V3）。

全部端点在 `/api/ticket/**` 下，**必须持访问令牌**（与其他票务接口一致的 JWT 鉴权）。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ticket/tickets/{ticketNo}/attachments` | 为票号上传附件（multipart 字段 `file`，`remark` 可选），单文件 ≤20MB |
| GET | `/api/ticket/tickets/{ticketNo}/attachments` | 按票号列出全部有效附件 |
| GET | `/api/ticket/attachments/{id}/download` | 单个附件下载（中文文件名 RFC 5987 编码） |
| GET | `/api/ticket/attachments/zip?ticketNos=T001&ticketNos=T002` | **整批 ZIP 流式下载**，票号自动去重保序，单包 ≤50 |
| DELETE | `/api/ticket/attachments/{id}?reason=` | 逻辑删除（元数据与物理文件保留追溯） |

要点：

- **单包 50 份硬上限**：`evops.attachment.zip-max-tickets=50`，代码内再封顶 50（调小不允许调大）；超限在**打开响应流之前**拒绝，回 `200 + success=false` JSON，绝不产出半截 ZIP；超过请分批下载。
- **流式不占内存**：ZIP 直写响应输出流，逐附件 8KB 缓冲拷贝（`ZipOutputStream` UTF-8），整包不进内存、不落临时盘。采用请求线程内同步写出（不用 `StreamingResponseBody` 异步分派，规避 Shiro 过滤器链在异步再分派时无 SecurityManager 的兼容问题）。
- **中文不乱码**：ZIP 条目名以 UTF-8 写入（带 EFS 语言标志位），按 `票号/原始中文文件名` 组织目录，同目录重名自动加序号；包名/单文件名响应头同时给 ASCII `filename` 兜底与 `filename*=UTF-8''` 原文（RFC 5987）。
- **下载清单**：包内根目录固定带 `下载清单.txt`（UTF-8 带 BOM，Excel 直开不乱码），逐条列出 序号/票号/包内文件，并写明生成时间、操作人、请求号、文件数；无附件的票号、物理文件缺失的附件单列提示，不中断整包。
- **存储安全**：落盘名是服务端生成的 UUID（仅保留安全扩展名），原始中文文件名只存库、不参与路径拼接；路径规范化校验防穿越；先写 `.tmp` 再原子改名，半成品不会被下载读到。
- **元数据**：记录 SHA-256、大小、ContentType、操作人/请求号/业务时间；按票号查询走 `(ticket_no, id)` 索引。
- **鉴权**：无访问令牌/令牌失效一律 401；删除为逻辑删除（`deleted=id`，MyBatis-Plus `@TableLogic`），物理文件留存由运维按策略清理。
- 样本数据为 3 张样本票各挂一个中文文件名票根（`TSH001000000004`、`TSH001000000005`、`TSH002000000001`），可直接联调整批下载。

### 配置（`evops.attachment`）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `storage-dir` / `EVOPS_ATTACHMENT_DIR` | `./data/attachments` | 附件受管存储目录（需纳入磁盘监控与备份） |
| `zip-max-tickets` / `EVOPS_ATTACHMENT_ZIP_MAX` | 50 | 单包票号上限（硬上限 50） |
| `max-upload-size` | 20MB | 单附件业务上限（容器 multipart 另限 20MB/请求 25MB） |

值班常见情况处置（401、超 50、乱码、文件缺失、下载中断、误删恢复等）见 [`docs/runbook/票附件批量下载-值班处置.md`](docs/runbook/票附件批量下载-值班处置.md)。

```bash
# 整批下载（注意重复传 ticketNos 参数；≤50 个）
curl -OJ "http://localhost:8080/api/ticket/attachments/zip?ticketNos=TSH001000000004&ticketNos=TSH002000000001" \
  -H 'Authorization: Bearer <accessToken>'

# 上传附件
curl -X POST "http://localhost:8080/api/ticket/tickets/TSH002000000001/attachments" \
  -H 'Authorization: Bearer <accessToken>' -F 'file=@/path/票根凭证.pdf'
```

## 检票关键节点通知场务（notify-1，Flyway V4）

观众检票（`SOLD→CHECKED`）事务提交后按事件自动通知当班场务：票/订单/场次/座位字段二次加工成模板变量，渲染成现场文案，经**站内信 + 邮件双通道**发送；逐通道发送记录入库可查，失败退避**自动重试 2 次**，模板改版只升版本、**不影响已发内容**，发送结果按批次/通道可统计。检票接口本身无任何变化。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/ticket/notify/inbox/messages` | 场务查本人站内信（按登录工号隔离），`readFlag` 过滤未读/已读 |
| GET | `/api/ticket/notify/inbox/unread-count` | 本人未读数 |
| POST | `/api/ticket/notify/inbox/messages/{id}/read` | 标记已读（幂等） |
| GET | `/api/ticket/notify/records` | 发送记录分页（`ticketNo/channel/status/dispatchId` 过滤） |
| GET | `/api/ticket/notify/records/{id}` | 记录详情：**渲染后标题/正文快照**、变量集、模板版本、回执、错误 |
| POST | `/api/ticket/notify/records/{id}/retry` | FAILED/RETRYING 手动立即重发 |
| GET | `/api/ticket/notify/stats?eventCode=&from=&to=` | 批次维度 + 通道维度发送统计 |
| GET/POST/PUT | `/api/ticket/notify/templates[/{id}]` | 模板维护；改标题/正文/事件码自动 `tpl_version+1` |
| GET/POST/PUT | `/api/ticket/notify/recipients[/{id}]` | 场务收件人维护（工号收站内信、邮箱收邮件，至少一个） |

要点：

- **按事件触发**：复用票生命周期事件 `TicketLifecycleEvent`，仅 `CHECK` 且在检票事务 **AFTER_COMMIT** 消费——回滚不通知；通知异常不外溢影响票务主流程。通知批次按 `(event_code, ticket_id)` 唯一，事件重放不重复通知。
- **数据二次加工**：`NotifyVarsBuilder` 把票号/订单号/演出/场次/场馆/区域排座/票价/检票时间/开演时间/检票人/备注汇成变量表，`TemplateRenderer` 替换 `${var}`（未提供变量替空串，不把占位原文发出去）。
- **双通道独立、邮件不拖检票**：站内信写本地信箱（`record_id` 唯一约束保证重试不产生重复信），在检票请求线程内**即时送达、当场可查**；邮件经 HTTP 投企业邮件出口网关（2xx+ACK OK 判成功），默认由**独立线程池异步外呼**（`evops.notify.email-async`，默认 true），检票接口不再被慢/抖动的邮件网关拖住。邮件首投若因线程池繁忙/线程异常/进程重启未发出，记录留 `PENDING`，每分钟定时任务在首投兜底时限（`pending-first-delivery-seconds`，默认 90s）到期后**原子认领补发**，不丢通知；`email-async=false` 可立即切回同步外呼旧行为。两通道开关独立，互不拖累。
- **发送记录即凭证**：渲染标题/正文/变量集在发送当次快照进 `t_notify_record`，记录当时模板版本；模板表文案版本独立列 `tpl_version`（与基类乐观锁 `version` 区分），改版对历史记录零影响。
- **失败重试 2 次**：首发失败置 `RETRYING`，退避 10s/20s（指数、上限 5min）由每分钟定时任务续投，重试 2 次仍败置 `FAILED`；批次据通道结果汇总 SUCCESS/PARTIAL/FAILED。收件人无邮箱/工号记 `SKIPPED`，不计故障。
- **邮件网关**：`evops.notify.email-endpoint` 指向出口网关；内置 `/mail-stub` 联调桩（不鉴权、内存邮箱、支持故障注入 `POST /mail-stub/_admin/fail-mode?times=N&status=500`），**上线必须把地址改为企业网关**。
- 样本数据：新增场务账号 `stage01`（周洋，口令同其他样本账号 `Venue@2026`）、收件人 2 名（场务+值班经理，双通道可达）、TICKET_CHECK 站内信/邮件模板各一套。

### 配置（`evops.notify`）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `enabled` / `EVOPS_NOTIFY_ENABLED` | true | 通知总开关 |
| `inbox-enabled` / `email-enabled` | true / true | 两通道独立开关 |
| `email-endpoint` / `EVOPS_NOTIFY_EMAIL_ENDPOINT` | `http://localhost:8080/mail-stub/send` | **邮件出口网关（上线必改）** |
| `max-retry` | 2 | 首发失败后的自动重试次数（验收口径固定 2） |
| `base/max-backoff-seconds` | 10 / 300 | 指数退避基数与上限 |
| `email-async` / `EVOPS_NOTIFY_EMAIL_ASYNC` | true | 邮件异步外呼，检票接口不等邮件网关；false 切回同步旧行为 |
| `email-pool-size` / `EVOPS_NOTIFY_EMAIL_POOL_SIZE` | 2 | 邮件外呼线程数（IO 等待型，2 足够） |
| `pending-first-delivery-seconds` / `EVOPS_NOTIFY_PENDING_FIRST_DELIVERY_SECONDS` | 90 | PENDING 邮件首投兜底时限（须 > connect+read 单次上限 8s） |

验收（步骤+截图，非技术人员可读）：[`docs/acceptance/检票通知场务-验收报告.md`](docs/acceptance/检票通知场务-验收报告.md)；
值班处置：[`docs/runbook/检票通知场务-值班处置.md`](docs/runbook/检票通知场务-值班处置.md)。

## 检票数据出系统统一脱敏（mask-1，Flyway V5）

安全组要求：检票单据的观众姓名/联系方式等敏感字段，**出系统一律打码**；仅白名单场务角色可见完整值，且每次看明文都留痕。打码在 Jackson 序列化期统一完成，接口与导出口径一致；规则按版本发布、历史版本留档可追溯。

- **注解标记**：敏感字段用 `@Sensitive(value=打码类型, bizType, bizIdField)` 标注（现落在 `AppointmentVO.visitorName/contact`）。打码类型：`NAME`（保留首字，周洋→周\*）、`CONTACT`（11 位手机号保留前 3 后 4，13812348000→138\*\*\*\*8000）、`GENERIC`（保留首尾）；对已打码值（含 `*`）再处理幂等。
- **序列化统一脱敏**：`SensitiveMaskModule` 只注册到对外 HTTP 的 ObjectMapper 副本；库内幂等快照、邮件载荷、平台 XML 等内部序列化用未注册模块的主 ObjectMapper，保持完整值。幂等回放快照反序列化为 `Map`（无字段注解），由 Object 属性层 `UntypedMaskingSerializer` + Map 递归兜底，**回放按当次请求人角色重新脱敏**——非白名单不能借重放看到明文。
- **规则按角色放行 + 留痕**：当前生效规则版本（`t_mask_rule_version`）配置白名单角色（初始 `STAGE_STAFF,ADMIN`）。角色取库内最新值（JWT 校验时回填，调整角色即时生效、无需重签令牌）。白名单看明文时，按「业务单据+字段+出口（API/EXPORT）」逐字段写 `t_sensitive_view_log`（同请求同字段去重）；请求结束统一刷库，写留痕失败打 ERROR 不影响响应。
- **规则变更留版本**：发布新版本（`rule_version+1`）时旧 ACTIVE 置 SUPERSEDED 留档、永不改写；内存规则缓存发布即失效、立即生效。规则缺失/读库异常**安全失败关闭**（全员打码）。
- **导出口径一致**：检票预约单据系统内导出 `GET /api/ticket/checkin/appointments/export`（CSV，UTF-8 BOM，Excel 直开），敏感列与接口走同一个 `MaskService`；单次行数硬上限 5000（只能调小），超限在打开响应流前拒绝、回 JSON，不产出半截文件。

| 方法 | 路径 | 访问 | 说明 |
|---|---|---|---|
| GET | `/api/ticket/checkin/appointments/export` | 访问令牌 | 检票预约单据 CSV 导出（过滤条件同分页；敏感列按角色脱敏，明文导出按 EXPORT 留痕） |
| GET | `/api/ticket/mask/rules` | 仅 ADMIN | 全部规则版本（含 SUPERSEDED 历史版本） |
| GET | `/api/ticket/mask/rules/current` | 仅 ADMIN | 当前唯一生效版本 |
| POST | `/api/ticket/mask/rules/publish` | 仅 ADMIN | 发布新版本（字段→打码类型 + 白名单角色），旧版留档、立即生效 |
| GET | `/api/ticket/mask/view-logs` | 仅 ADMIN | 明文查看留痕明细分页（按人/角色/业务单据/出口/时间过滤） |

要点：

- **端到端口径**：创建/详情/分页/占用/冲突响应、幂等快照回放、CSV 导出，凡敏感字段出系统都走同一判定；无登录态/角色未知/规则缺失一律打码（失败关闭）。
- **样本账号**：场务岗 `stage01`（周洋）角色由 `STAFF` 细化为 `STAGE_STAFF`（V5 对旧库 UPDATE，新库 seeder 直接播），属白名单可见明文；`box_office01`/`ticket_check01`（STAFF）看到打码值。
- 上线顺序：**先备份（H2 文件 + 源码）→ 再变更（V5 随启动自动迁移，幂等）→ 后验证（见验收报告）**。V5 显式播种规则版本 id=1 后已把 IDENTITY 序列拨到 2，首次发布新版本不撞主键。

### 配置（`evops.mask`）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `export-max-rows` / `EVOPS_MASK_EXPORT_MAX` | 5000 | 检票单据单次导出行数上限（硬上限 5000，只能调小） |

验收（步骤+证据，非技术人员可读）：[`docs/acceptance/检票数据脱敏-验收报告.md`](docs/acceptance/检票数据脱敏-验收报告.md)；
值班处置：[`docs/runbook/检票数据脱敏-值班处置.md`](docs/runbook/检票数据脱敏-值班处置.md)。

## 一人多岗权限模型（rbac-1，Flyway V6）

上个季度提出的权限整改：票务数据要管得更准。模型从「一人一个角色字符串」升级为**一人多岗 RBAC**：

- **多岗取并集（不是交集）**：账号可绑多个角色（`t_admin_user_role`），接口权限码按所有**启用中**角色求并集，命中任一即放行。多岗共有的菜单/权限只出现一次。
- **菜单树按并集动态装配**：菜单/权限点为一棵树（`t_rbac_menu`：DIR 目录 / MENU 菜单 / BUTTON 权限点），登录后 `GET /api/auth/menus` 实时返回该账号并集菜单树 + 扁平权限码集（供按钮显隐）；父节点被停用的悬挂节点不提升为根。
- **停用角色即时收权**：访问令牌校验时（`AuthService.authenticate`）每请求从库实时装载角色集与权限集，**不做缓存**。停用角色、解绑岗位、收回菜单授权、停用菜单节点，当事人持旧 JWT 无需重登，**下一请求即 403**；其仍启用的其它岗位不受影响。
- **越权统一拦截并记录**：业务方法标注 `@RequirePermission("ticket:order:create")`（方法级覆盖类级；ADMIN 代码层短路），由 `RbacInterceptor`（order=3，认证之后）统一判定；不足统一回 403（ApiResponse JSON）并向 `t_rbac_deny_log` 追加一行（工号/当时角色并集/方法路径/所需权限/IP/请求号/时间），审计写库失败只打 ERROR、不影响 403。
- **管理端（仅 ADMIN，`/api/admin/rbac`）**：角色 CRUD + 启停、用户多岗覆盖式分配（空数组=下岗）、菜单树 CRUD、角色授权覆盖式重写、越权记录分页查询；内置 ADMIN 角色禁停用禁删除（防自锁）。
- **兼容**：`t_admin_user.role` 单值列保留为「主岗位」（随授岗同步为首个启用岗位），JWT 载荷不变；脱敏白名单改为「岗位并集与白名单有交集即见明文」，明文留痕记录实际命中白名单的岗位（多岗命中时优先记业务岗而非 ADMIN）。

| 方法 | 路径 | 访问 | 说明 |
|---|---|---|---|
| GET  | `/api/auth/menus` | 访问令牌 | 我的菜单树 + 权限码并集 |
| GET/POST/PUT/DELETE | `/api/admin/rbac/roles[...]` | 仅 `rbac:manage`（ADMIN） | 角色列表/新建/改名/启停/删除 |
| GET/PUT | `/api/admin/rbac/users[/{id}/roles]` | 仅 ADMIN | 用户分页、查岗、覆盖式授岗 |
| POST | `/api/admin/rbac/users/{id}/disable` `/enable` | 仅 ADMIN | 停用/启用账号：停用即全端令牌失效，重启用旧会话不复活 |
| GET/POST/PUT/DELETE | `/api/admin/rbac/menus[...]` | 仅 ADMIN | 全量维护树、节点 CRUD |
| GET/PUT | `/api/admin/rbac/roles/{id}/menus` | 仅 ADMIN | 查看/覆盖式角色授权 |
| GET  | `/api/admin/rbac/deny-logs` | 仅 ADMIN | 越权记录分页（工号/路径/时间） |

内置角色：`ADMIN`（全部）、`STAFF`（前台通岗，兼容存量）、`BOX_OFFICE` 售票岗、`TICKET_CHECK` 检票岗、`STAGE_STAFF` 场务岗。

值班处置（贴工位、含 curl）：[`docs/runbook/一人多岗权限-值班处置.md`](docs/runbook/一人多岗权限-值班处置.md)。

## 检票备注敏感词扫描（sensitive-1，Flyway V7）

场馆手工台账错漏不少：检票备注等自由文本入系统前统一过一遍敏感词。**DFA（Trie 自动机）扫描**，词库分两级——**高危命中直接拦截本次操作，低危命中片段打码后放行**；每次命中留痕（原文 + 码点位置），事后可审计回查。

- **扫描入口**：检票/退票/票作废/整单作废的 `reason`、取消排期的 `cancelReason`，在状态流转改库**之前**过闸门（`SensitiveScanService`）。高危抛 `SensitiveBlockException`（HTTP 200 + success=false），事务整体回滚、库行不动；低危把命中片段逐码点替换为 `*` 后照常落库（流水/单据里存打码文本）。
- **全角半角干扰归一**：NFKC 折叠（全角 Ａ→A、全角数字/标点→半角）+ 大小写折叠 + 跳过空白/零宽字符/插缝符（`· . * ~ - _`）。「黄 牛」「黄·牛」「加Ｖ」都按「黄牛」「加v」命中；句子标点（，。）不算插缝符，不误判。
- **命中位置准确**：归一骨架与原码点下标并行保存，命中区间映射回**原文**码点半开区间 `[startCp,endCp)`（含被跳过的干扰符，「黄 牛」整体打码）；词库嵌套时贪婪最长匹配（「代开发票」优先于「发票」），命中区间互不重叠。
- **命中留痕**：`t_sensitive_hit_log` 逐词一行（只追加）——场景、票号/预约号、字段名、命中词与级别、**原文片段与完整原文**、码点起止位置、打码后文本、处置（BLOCKED/MASKED）、操作人/请求号。高危拦截的留痕走**独立事务**（REQUIRES_NEW），业务回滚也保得住；留痕写库失败只打 ERROR，不影响业务。
- **词库分级管理**：`t_sensitive_word`（HIGH 拦截 / LOW 打码；ENABLED 生效 / DISABLED 停用留档）。启动装载全部生效词构建不可变 DFA；管理端增删/停用后**立即热刷新**（原子切换，读路径无锁）。入库自动归一，同形词（全角/插缝变体）拒绝重复入库；同归一形态重复时 HIGH 覆盖 LOW。
- **回退预案（先备好，一出异常立即切回旧逻辑）**：① `evops.sensitive.enabled=false` 总开关，完全不扫描，行为与上线前一致；② `fail-open=true`（默认）扫描链路自身异常记 ERROR 后按原文放行，**现场检票绝不被扫描故障挡住**；③ 个别词误伤由 ADMIN 停用该词（立即生效）或 `/reload` 整库重刷。平台入站报文默认豁免扫描（`scan-agent-inbound`，非人工输入，误拦会导致平台同步失败）。

| 方法 | 路径 | 访问 | 说明 |
|---|---|---|---|
| GET | `/api/ticket/sensitive/words` | 仅 ADMIN | 词库列表（级别/状态/词文本过滤） |
| POST | `/api/ticket/sensitive/words` | 仅 ADMIN | 新增词条（同形拒绝，提交即生效） |
| POST | `/api/ticket/sensitive/words/{id}/disable` `/enable` | 仅 ADMIN | 停用/启用词条（热刷新） |
| POST | `/api/ticket/sensitive/reload` | 仅 ADMIN | 手动重刷 DFA，返回生效词数 |
| GET | `/api/ticket/sensitive/hits` | 仅 ADMIN | 命中留痕分页（场景/级别/处置/关键词过滤） |
| POST | `/api/ticket/sensitive/preview` | 仅 ADMIN | 试扫：返回命中词/级别/原文片段/码点位置，不落业务不留痕 |

### 配置（`evops.sensitive`）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `enabled` / `EVOPS_SENSITIVE_ENABLED` | true | 总开关：false 立即完全切回不扫描的旧逻辑 |
| `fail-open` / `EVOPS_SENSITIVE_FAIL_OPEN` | true | 扫描链路异常按原文放行（不挡现场检票）；false 为故障强卡（演练用） |
| `scan-agent-inbound` / `EVOPS_SENSITIVE_SCAN_AGENT` | false | 代理平台入站报文是否扫描（默认豁免） |
| `mask-char` | `*` | 低危命中的打码字符（逐码点替换） |

值班处置（回退预案、误伤处理、词库维护，含 curl）：[`docs/runbook/检票备注敏感词-值班处置.md`](docs/runbook/检票备注敏感词-值班处置.md)。

## 场馆后台 JWT 双令牌认证（auth-1）

落实内部审计整改意见：访问令牌有效期短，刷新令牌轮换一次性使用，刷新后旧令牌立即失效，登出与改密都拉黑在用令牌，改密全端下线。

### 端点（前缀 `/api/auth`）

| 方法 | 路径 | 访问 | 说明 |
|---|---|---|---|
| POST | `/api/auth/login` | 匿名 | 账号密码换双令牌 |
| POST | `/api/auth/refresh` | 匿名 | 刷新令牌一次性轮换；**旧令牌重放触发家族连坐** |
| POST | `/api/auth/logout` | 访问令牌 | 拉黑当前 access 的 jti，可选撤销本设备 refresh |
| POST | `/api/auth/change-password` | 访问令牌 | 校验原密码；改密后全端下线，必须用新密码重新登录 |
| GET  | `/api/auth/me` | 访问令牌 | 当前账号信息（id/工号/姓名/场馆/主岗位 role + 全部在岗 roles） |
| GET  | `/api/auth/menus` | 访问令牌 | 当前账号菜单树（多岗并集、动态装配、去重）+ 扁平权限码集 |

票务接口（`/api/ticket/**`）现在都需要访问令牌；健康检查 `/api/health` 与 login/refresh 保持匿名。

### 安全机制

- **访问令牌**：JWT(HS256) 自实现（JDK+Jackson，未引第三方库），有效期 **15 分钟**，带 `jti`、`tv`（密码版本）、`typ=access`。
- **刷新令牌**：不透明随机串（32 字节），有效期 **7 天**；明文只在响应里出现一次，库里只存 SHA-256 哈希。
- **一次性轮换**：每次 refresh 在库内条件更新 `ACTIVE→USED`（影响行数 0 即重放），再签发同一 `family_id` 新链节；旧 refresh 立刻失效。**轮换成功的同一事务把配对的旧 access 按 jti 拉黑（`reason=ROTATED`），旧访问令牌下一请求即 401**，不允许新旧两枚 access 在剩余有效期内并行；只作用于被轮换的设备家族，其他设备互不影响。
- **重放连坐**：已 USED/REVOKED 的刷新令牌再次使用视为泄露，撤销同一 `family_id`（同一登录设备链）全部 ACTIVE 令牌；撤销走 REQUIRES_NEW 独立事务，**先落库再抛 401**，不随业务回滚。
- **登出**：当前 access 按 jti 入 `t_token_blacklist`（重复登出幂等），同时撤销本设备 refresh。
- **改密全端下线**：`token_version` 自增，所有旧 access 因 tv 不匹配立即失效；全部设备的 ACTIVE refresh 批量撤销；当前 access 一并拉黑。
- **停用账号全端失效**：ADMIN 停用账号（`/api/admin/rbac/users/{id}/disable`）后，`status=DISABLED` 使现有 access/refresh/登录下一请求全部 401；同时 tv 自增、撤销全部 refresh，重新启用后停用前旧会话也不复活，必须重登。
- **口令存储**：PBKDF2-HMAC-SHA256（120000 轮、16 字节随机盐），用户名/密码错误统一文案防探测。
- **审计联动**：已登录请求的票务写操作，操作人自动取登录工号（`X-Operator-Id` 仍可显式覆盖）。
- **存证清理**：黑名单与过期刷新令牌由每日 03:17 的定时任务清除。

### 配置（`evops.auth`）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `secret` / `EVOPS_AUTH_SECRET` | 演示固定值（必须替换） | HS256 密钥，≥32 字节；生产必须经环境变量注入 |
| `access-token-ttl-seconds` | 900（15 分钟） | 访问令牌有效期 |
| `refresh-token-ttl-seconds` | 604800（7 天） | 刷新令牌有效期 |
| `issuer` | `evops-venue-admin` | JWT `iss` |

### 演示账号与真实样本

首启自动播种（幂等，账号已存在则跳过）。样本取自**滨江大剧院**真实业务结构，非临时拼装：

- 5 个岗位账号，初始密码均为 `Venue@2026`（建议登录后立即改密）；后两个由检票通知/RBAC 播种补齐：

| 工号 | 姓名 | 主岗位 | 在岗集合（一人多岗） | 场馆 |
|---|---|---|---|---|
| `venue_admin` | 王芳 | ADMIN | ADMIN | 滨江大剧院 |
| `box_office01` | 李强 | STAFF | STAFF 前台通岗 + BOX_OFFICE 售票岗 | 滨江大剧院 |
| `ticket_check01` | 赵敏 | STAFF | STAFF 前台通岗 + TICKET_CHECK 检票岗 | 滨江大剧院 |
| `stage01` | 周洋 | STAGE_STAFF | STAGE_STAFF 场务岗（脱敏白名单） | 滨江大剧院 |
| `multi_ops01` | 陈晨 | BOX_OFFICE | **BOX_OFFICE + TICKET_CHECK（一人多岗样板）** | 滨江大剧院 |

- 票务底账：26 个座位（一层池座 16 / 二层楼座 6 / VIP 包厢 4）、2 个场次；
  已结束场次 5 张订单 9 张票覆盖 **正常在售 / 检票入场 / 退票释放 / 整单作废** 完整业务链（带真实格式订单号、票号、请求号、反向流水追溯链）；在售票场次《天鹅湖·芭蕾经典》(SH002) 可现场继续售票检票，票号从样本计数连续分配。
- 票附件样本：`TSH001000000004`、`TSH001000000005`、`TSH002000000001` 各挂 1 个中文文件名票根，供客服整批 ZIP 下载联调（见 ticket-2）。

### 调用示例

```bash
# 登录
curl -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"box_office01","password":"Venue@2026"}'

# 业务请求
curl http://localhost:8080/api/ticket/tickets/TSH001000000006 \
  -H 'Authorization: Bearer <accessToken>'

# 刷新（响应里的 refreshToken 下次轮换用，旧的立即失效）
curl -X POST http://localhost:8080/api/auth/refresh -H 'Content-Type: application/json' \
  -d '{"refreshToken":"<refreshToken>"}'

# 登出（可带 refreshToken 一并撤销本设备）
curl -X POST http://localhost:8080/api/auth/logout -H 'Authorization: Bearer <accessToken>' \
  -H 'Content-Type: application/json' -d '{"refreshToken":"<refreshToken>"}'

# 改密（全端下线，随后用新密码重新登录）
curl -X POST http://localhost:8080/api/auth/change-password \
  -H 'Authorization: Bearer <accessToken>' -H 'Content-Type: application/json' \
  -d '{"oldPassword":"Venue@2026","newPassword":"新密码8位以上"}'
```



