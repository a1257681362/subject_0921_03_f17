package com.evops.ticket.notify.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 邮件网关联调桩（/mail-stub，不走后台鉴权）。
 *
 * <p>真实部署把 evops.notify.email-endpoint 指向企业邮件出口网关即可，本桩不下发真邮件。
 * 故障注入（验收/联调用）：
 * <ul>
 *   <li>单次请求级：请求头 {@code X-Stub-Fail-Status: 500} 只让本次返回 5xx；</li>
 *   <li>故障模式：{@code POST /mail-stub/_admin/fail-mode?times=N&status=500}，
 *       接下来 N 封一律返回故障，N 次后自动恢复，用于演示自动重试与耗尽。</li>
 * </ul>
 * 收到的邮件保留在内存邮箱，{@code GET /mail-stub/inbox} 可查看（重启清空）。
 */
@RestController
@RequestMapping("/mail-stub")
public class MailStubController {

    private static final Logger log = LoggerFactory.getLogger(MailStubController.class);

    private final ConcurrentLinkedQueue<Map<String, Object>> inbox = new ConcurrentLinkedQueue<>();
    private final AtomicInteger failureLeft = new AtomicInteger(0);
    private final AtomicInteger failureStatus = new AtomicInteger(500);
    private final AtomicInteger rejectedCount = new AtomicInteger(0);

    @PostMapping("/send")
    public Map<String, Object> send(@RequestBody Map<String, Object> payload,
                                    @RequestHeader(value = "X-Stub-Fail-Status", required = false) Integer failStatus) {
        Integer modeFail = failureLeft.get() > 0 ? failureStatus.get() : null;
        int status = failStatus != null ? failStatus : (modeFail != null ? modeFail : 200);
        if (failStatus != null) {
            rejectedCount.incrementAndGet();
        } else if (modeFail != null) {
            failureLeft.decrementAndGet();
            rejectedCount.incrementAndGet();
        }
        if (status != 200) {
            log.warn("[邮件桩] 模拟网关拒收 status={} to={} 剩余故障次数={}",
                    status, payload.get("to"), failureLeft.get());
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("code", "GATEWAY_ERROR");
            resp.put("message", "模拟网关故障 HTTP " + status);
            throw new StubHttpException(status, resp);
        }
        String messageId = "MID-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        Map<String, Object> mail = new LinkedHashMap<>(payload);
        mail.put("messageId", messageId);
        mail.put("receivedAt", LocalDateTime.now().toString());
        inbox.add(mail);
        log.info("[邮件桩] 已收下邮件 messageId={} to={} subject={}",
                messageId, payload.get("to"), payload.get("subject"));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", "OK");
        resp.put("messageId", messageId);
        return resp;
    }

    /** 内存邮箱查看（验收演示用）：可按 to 过滤。 */
    @GetMapping("/inbox")
    public Map<String, Object> listInbox(@RequestParam(required = false) String to) {
        List<Map<String, Object>> mails = inbox.stream()
                .filter(m -> to == null || to.equals(String.valueOf(m.get("to"))))
                .collect(Collectors.toList());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", mails.size());
        resp.put("rejected", rejectedCount.get());
        resp.put("mails", new ArrayList<>(mails));
        return resp;
    }

    /** 故障模式：接下来 times 封返回 status 故障；times=0 立即恢复。 */
    @PostMapping("/_admin/fail-mode")
    public Map<String, Object> failMode(@RequestParam(defaultValue = "3") int times,
                                        @RequestParam(defaultValue = "500") int status) {
        failureLeft.set(Math.max(0, times));
        failureStatus.set(status);
        log.warn("[邮件桩] 故障模式已设置：接下来 {} 封返回 HTTP {}", times, status);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("failureLeft", failureLeft.get());
        resp.put("failureStatus", status);
        return resp;
    }

    @GetMapping("/_admin/fail-mode")
    public Map<String, Object> failModeView() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("failureLeft", failureLeft.get());
        resp.put("failureStatus", failureStatus.get());
        return resp;
    }

    /** 让桩以指定 HTTP 状态码返回的异常，由 @ExceptionHandler 转成 HTTP 响应。 */
    static class StubHttpException extends RuntimeException {
        private final int status;
        private final Map<String, Object> body;

        StubHttpException(int status, Map<String, Object> body) {
            this.status = status;
            this.body = body;
        }

        public int getStatus() { return status; }
        public Map<String, Object> getBody() { return body; }
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(StubHttpException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> handle(StubHttpException e) {
        return org.springframework.http.ResponseEntity.status(e.getStatus()).body(e.getBody());
    }
}
