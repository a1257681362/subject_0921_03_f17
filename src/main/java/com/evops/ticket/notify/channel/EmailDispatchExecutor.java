package com.evops.ticket.notify.channel;

import com.evops.ticket.notify.config.NotifyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 邮件外呼专用线程池。
 *
 * <p>检票接口在通知事务提交后不应被外部邮件网关拖住：邮件 HTTP 外呼（connect 3s +
 * read 5s，失败还要重试调度）放到本池异步执行，检票线程只完成本地站内信即返回。
 *
 * <p>拒绝策略：池和队列都满时抛出 {@link RejectedExecutionException}（不回退到调用线程，
 * 否则极端情况下又把检票请求卡住），由调用方捕获后保留记录 PENDING，
 * 每分钟定时任务在"首投兜底时限"到期后补投，不丢通知。
 *
 * <p>停机：等待队列里的外呼任务执行完（最多 10s），避免应用重启丢首发；
 * 没跑完的记录仍是 PENDING/RETRYING，重启后由定时任务续投。
 */
@Component
public class EmailDispatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatchExecutor.class);

    /** 待外呼队列上限：排队超过该值说明邮件网关长时间不可用，新通知转 PENDING 兜底，不堆积内存 */
    private static final int QUEUE_CAPACITY = 200;

    private final NotifyProperties properties;
    private ThreadPoolExecutor executor;

    public EmailDispatchExecutor(NotifyProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        CustomizableThreadFactory threadFactory = new CustomizableThreadFactory("notify-email-");
        threadFactory.setDaemon(true);
        executor = new ThreadPoolExecutor(
                properties.getEmailPoolSize(), properties.getEmailPoolSize(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 提交一次邮件外呼。
     *
     * @return true 已被线程池受理；false 池满拒绝（调用方应保留 PENDING 等定时兜底）
     */
    public boolean submit(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("邮件外呼线程池已满（{}），本次首投保留 PENDING，由定时任务到点兜底续投",
                    QUEUE_CAPACITY);
            return false;
        }
    }

    @PreDestroy
    public void shutdown() {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("停机等待邮件外呼队列 10s 未清空，剩余 {} 条将在重启后由定时任务续投",
                        executor.shutdownNow().size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
