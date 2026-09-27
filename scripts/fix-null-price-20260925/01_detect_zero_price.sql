-- =====================================================================
-- 只读排查：代理回传报文空金额节点被按 0 入账（2026-09 票款汇总缺口）
-- 库口径：H2 文件库（现场 ./data/evops）。本脚本不写任何数据。
--
-- 背景：报文 <price></price>/缺省语义应为"金额未知"，历史代码却按 0
--   入账并参与票款汇总。修复已在应用层拦截；本脚本定位存量受影响数据。
--
-- 用法：
--   java -cp <h2.jar> org.h2.tools.Shell -url jdbc:h2:file:./data/evops -user sa \
--     -sql "$(cat 01_detect_zero_price.sql)"
-- =====================================================================

-- 【清单 1】price=0 的票：可能是"空金额被当 0"，也可能是合法赠票/免票，
--          一律列出交财务逐笔核实。附该票最近一条代理报文里的 price 标签原文，
--          报文带真实售价的可据此订正；报文中 price 也为空的须找代理补报。
SELECT t.id                AS ticket_id,
       t.ticket_no,
       t.order_id,
       o.order_no,
       t.price             AS ticket_price,
       t.status            AS ticket_status,
       t.biz_time,
       (SELECT REGEXP_SUBSTR(m.payload, '<price>[^<]*</price>')
          FROM t_agent_message m
         WHERE m.ticket_no = t.ticket_no
         ORDER BY m.id DESC
         LIMIT 1)          AS agent_price_tag_latest
  FROM t_ticket t
  JOIN t_sale_order o ON o.id = t.order_id
 WHERE t.deleted = 0
   AND t.price = 0
 ORDER BY t.id;

-- 【清单 2】订单汇总与票明细之和不一致：total_amount 被错误累加（含/漏 0 价票）的订单。
--          订正脚本 02 按票明细重算；本清单先给财务确认影响面。
SELECT o.id                 AS order_id,
       o.order_no,
       o.ticket_count,
       o.total_amount       AS total_amount_before,
       COALESCE(SUM(t.price), 0) AS sum_of_tickets,
       (o.total_amount - COALESCE(SUM(t.price), 0)) AS diff_amount,
       o.status             AS order_status,
       o.biz_time
  FROM t_sale_order o
  LEFT JOIN t_ticket t ON t.order_id = o.id AND t.deleted = 0
 WHERE o.deleted = 0
 GROUP BY o.id, o.order_no, o.ticket_count, o.total_amount, o.status, o.biz_time
HAVING o.total_amount <> COALESCE(SUM(t.price), 0)
 ORDER BY o.id;

-- 【汇总数】受影响票笔数、订单数、汇总差额（负数=少计的票款）。
SELECT (SELECT COUNT(*) FROM t_ticket WHERE deleted = 0 AND price = 0) AS zero_price_ticket_count,
       (SELECT COUNT(*) FROM (
           SELECT o2.id
             FROM t_sale_order o2
             LEFT JOIN t_ticket t2 ON t2.order_id = o2.id AND t2.deleted = 0
            WHERE o2.deleted = 0
            GROUP BY o2.id, o2.total_amount
           HAVING o2.total_amount <> COALESCE(SUM(t2.price), 0)
       )) AS mismatched_order_count,
       (SELECT COALESCE(SUM(o3.total_amount - s.s_t), 0)
          FROM t_sale_order o3
          JOIN (SELECT order_id, SUM(price) AS s_t FROM t_ticket
                 WHERE deleted = 0 GROUP BY order_id) s ON s.order_id = o3.id
         WHERE o3.deleted = 0 AND o3.total_amount <> s.s_t) AS total_diff_negative_is_under;
