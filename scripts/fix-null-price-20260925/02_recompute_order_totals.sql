-- =====================================================================
-- 订正：代理回传空金额节点按 0 入账（2026-09 票款汇总缺口）
-- 库口径：H2 文件库（现场 ./data/evops）。
--
-- 原则：
--   1) 只做"可确定性重算"的部分——t_sale_order.total_amount 按票明细之和回算，
--      语句幂等，无差异即 0 行更新，可重复执行；
--   2) 绝不臆造票单价：t_ticket.price=0 且真实金额未知的票，无法从本库推断真值，
--      必须拿 01 清单找代理平台补报真实 <price> 后人工订正，本脚本不碰这些行；
--   3) 动库前先物理备份（见 README），先在备份库试跑本脚本再上现场。
--
-- 建议在 H2 Shell 单事务内执行并核对更新行数后再 COMMIT：
--   java -cp <h2.jar> org.h2.tools.Shell -url jdbc:h2:file:./data/evops -user sa
-- =====================================================================

-- 1) 按票明细重算订单汇总（仅更新确有差异的订单；无差异时 0 行，幂等）
UPDATE t_sale_order o
   SET o.total_amount = COALESCE((
           SELECT SUM(t.price)
             FROM t_ticket t
            WHERE t.order_id = o.id
              AND t.deleted = 0
       ), 0),
       o.update_time = CURRENT_TIMESTAMP
 WHERE o.deleted = 0
   AND o.total_amount <> COALESCE((
           SELECT SUM(t.price)
             FROM t_ticket t
            WHERE t.order_id = o.id
              AND t.deleted = 0
       ), 0);

-- 2) 订正后复查：应返回 0 行
SELECT o.id, o.order_no, o.total_amount, COALESCE(SUM(t.price), 0) AS sum_of_tickets
  FROM t_sale_order o
  LEFT JOIN t_ticket t ON t.order_id = o.id AND t.deleted = 0
 WHERE o.deleted = 0
 GROUP BY o.id, o.order_no, o.total_amount
HAVING o.total_amount <> COALESCE(SUM(t.price), 0);

-- 3) 人工待办（不自动改）：price=0 的票逐笔核实——赠票/免票保留 0；
--    属"空金额被当 0"的，凭代理补报的真实 <price> 逐票 UPDATE 并同步重算其订单：
--    UPDATE t_ticket SET price=<真值>, update_time=CURRENT_TIMESTAMP WHERE ticket_no='<票号>';
--    然后重跑本脚本第 1 段回算对应订单 total_amount。
SELECT t.ticket_no, o.order_no, t.status, t.biz_time
  FROM t_ticket t
  JOIN t_sale_order o ON o.id = t.order_id
 WHERE t.deleted = 0 AND t.price = 0
 ORDER BY t.id;
