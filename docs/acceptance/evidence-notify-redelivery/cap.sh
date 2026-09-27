D=/workspace/docs/acceptance/evidence-notify-redelivery
B=http://127.0.0.1:8099
CK=$(curl -s -X POST $B/api/auth/login -H 'Content-Type: application/json' -d '{"username":"ticket_check01","password":"Venue@2026"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
AD=$(curl -s -X POST $B/api/auth/login -H 'Content-Type: application/json' -d '{"username":"venue_admin","password":"Venue@2026"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")
CA="Authorization: Bearer $CK"; AA="Authorization: Bearer $AD"
TNO=$(curl -s "$B/api/ticket/tickets?status=SOLD&size=50" -H "$CA" | python3 -c "import sys,json;print(next(t['ticketNo'] for t in json.load(sys.stdin)['data']['list'] if t['ticketNo'].startswith('TSH002')))")
echo "$TNO" > $D/ticketNo.txt
echo "== 01 检票请求/响应 =="
curl -s -X POST $B/api/ticket/tickets/$TNO/check -H "$CA" -H 'X-Request-Id: EVD-RED-001' -H 'Content-Type: application/json' -d '{"reason":"现场检票"}' | tee $D/01_check_response.json | python3 -m json.tool
sleep 0.6
echo; echo "== 02 检票后 0.6s 发送记录（邮件应 SENDING 在途）=="
curl -s "$B/api/ticket/notify/records?ticketNo=$TNO&size=50" -H "$AA" | tee $D/02_records_inflight.json | python3 -c "import sys,json;[print('  id=%-2s %-5s %-4s %s'%(r['id'],r['channel'],r['recipientName'],r['status'])) for r in json.load(sys.stdin)['data']['list']]"
RID=$(curl -s "$B/api/ticket/notify/records?ticketNo=$TNO&size=50" -H "$AA" | python3 -c "import sys,json;print(next(r['id'] for r in json.load(sys.stdin)['data']['list'] if r['channel']=='EMAIL'))")
echo "$RID" > $D/emailRecordId.txt
echo; echo "== 03 在途点【手动重发】→ 原子去重拒绝（不会再发一封）=="
curl -s -X POST $B/api/ticket/notify/records/$RID/retry -H "$AA" | tee $D/03_retry_rejected.json | python3 -m json.tool
echo; echo "== 等待慢网关(4s)投递完成 =="; sleep 6
echo "== 04 最终发送记录（全部 SUCCESS）=="
curl -s "$B/api/ticket/notify/records?ticketNo=$TNO&size=50" -H "$AA" | tee $D/04_records_final.json | python3 -c "import sys,json;[print('  id=%-2s %-5s %-4s %s'%(r['id'],r['channel'],r['recipientName'],r['status'])) for r in json.load(sys.stdin)['data']['list']]"
echo; echo "== 05 邮件网关实际收到的邮件（应为 2 封，无第 3 封）=="
curl -s localhost:8098/count | tee $D/05_gateway_count.json | python3 -m json.tool
echo; echo "== 06 该邮件记录详情：渲染快照 + 变量集（票号/称呼不串号的凭证）=="
curl -s "$B/api/ticket/notify/records/$RID" -H "$AA" | tee $D/06_record_detail.json | python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
print('  recordId   :',d['id'])
print('  ticketNo   :',d['ticketNo'])
print('  channel    :',d['channel'],' status:',d['status'])
print('  标题(渲染快照):',d['titleRendered'])
print('  正文(渲染快照):')
for ln in d['contentRendered'].splitlines(): print('    '+ln)
print('  变量集快照  :',d['varsSnapshot'])
print('  httpStatus :',d['httpStatus'],' messageId:',d['messageId'])
"
