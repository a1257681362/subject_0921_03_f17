# 代理报文空金额按 0 处理 · 存量数据排查/订正（2026-09-25）

## 背景
代理回传报文 `<price></price>`（空=金额未知）曾被系统按 0 入账并参与票款汇总，导致票款少计。
应用层已修复（空值整单拒绝、空≠0）。本目录脚本用于排查/订正**历史存量**数据。
详见 `docs/acceptance/代理报文空金额按零处理-排查与修复工单.md`。

## 脚本
| 文件 | 性质 | 作用 |
|---|---|---|
| `01_detect_zero_price.sql` | **只读** | 列出 price=0 的票（附最近代理报文 price 原文）、汇总与明细不一致的订单、影响面合计 |
| `02_recompute_order_totals.sql` | 写（幂等） | 按票明细之和重算 `t_sale_order.total_amount`；复查差异；列出待人工核实的 0 价票 |

票单价真值**不臆造**：`t_ticket.price=0` 且真实金额未知的票，脚本不改，须凭代理补报的
真实 `<price>` 人工 `UPDATE t_ticket` 后重跑 `02` 回算订单汇总。

## 操作步骤（已在 2026-09-25 执行过一遍）
```bash
H2JAR=/tmp/h2x/h2-2.1.214.jar          # 或从 fat jar 解出 BOOT-INF/lib/h2-*.jar
DB=/workspace/data/evops               # 现场 H2 文件库（执行前停应用）

# 1) 备份（动库前必做）
mkdir -p /workspace/backup/pre-null-price-$(date +%Y%m%d)
cp -p ${DB}.mv.db /workspace/backup/pre-null-price-$(date +%Y%m%d)/
sha256sum ${DB}.mv.db /workspace/backup/pre-null-price-*/evops.mv.db

# 2) 备份库试跑（把备份恢复到隔离目录，先 01 后 02，确认结果与幂等）
mkdir -p /tmp/trial && cp -p /workspace/backup/pre-null-price-*/evops.mv.db /tmp/trial/evops.mv.db
java -cp $H2JAR org.h2.tools.Shell -url jdbc:h2:file:/tmp/trial/evops -user sa \
  -sql "$(cat 01_detect_zero_price.sql)"
java -cp $H2JAR org.h2.tools.Shell -url jdbc:h2:file:/tmp/trial/evops -user sa \
  -sql "$(cat 02_recompute_order_totals.sql)"

# 3) 试跑确认无误后再上现场（只读排查 + 幂等订正）
java -cp $H2JAR org.h2.tools.Shell -url "jdbc:h2:file:$DB;ACCESS_MODE_DATA=r" -user sa \
  -sql "$(cat 01_detect_zero_price.sql)"
java -cp $H2JAR org.h2.tools.Shell -url "jdbc:h2:file:$DB" -user sa \
  -sql "$(cat 02_recompute_order_totals.sql)"
```

## 2026-09-25 执行结果
- 备份：`backup/pre-null-price-20260925/evops.mv.db`（sha256 与源一致）。
- 试跑库注入缺陷数据验证：排查命中少计 380 订单与 0 价票；订正后汇总 380→760、复查 0 差异、重跑 0 行（幂等）。
- 现场库：0 张 0 价票、0 个失真订单，订正 0 行；订正前后与备份逐值一致（票款合计=订单汇总合计=5080.00）。
