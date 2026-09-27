package com.evops.ticket.service;

import com.evops.common.web.RequestContext;
import com.evops.ticket.entity.IdempotentRequest;
import com.evops.ticket.mapper.IdempotentRequestMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * 写请求幂等模板。
 *
 * 占坑 INSERT 与业务在同一事务：首次请求插入 PROCESSING 行，业务成功后置 DONE 并写响应快照；
 * 重复请求（同 X-Request-Id）命中唯一约束后直接回放首次结果，不执行任何业务。
 * 首次业务回滚时占坑行随事务一并回滚，无垃圾残留。
 */
@Service
public class IdempotentService {

    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_DONE = "DONE";

    private final IdempotentRequestMapper idempotentMapper;
    private final ObjectMapper objectMapper;

    public IdempotentService(IdempotentRequestMapper idempotentMapper, ObjectMapper objectMapper) {
        this.idempotentMapper = idempotentMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * @param requestHash 请求体摘要（{@link #hash(Object)}），防止 requestId 复用到不同请求
     * @param action      业务动作，返回业务主键与响应数据
     * @return 首次执行为业务数据；重复请求为快照反序列化结果（结构与首次一致）
     */
    @Transactional(rollbackFor = Exception.class)
    public Object execute(String requestHash, Supplier<IdempotentResult<?>> action) {
        String requestId = RequestContext.requestId();
        if (requestId == null) {
            // 理论不可达：拦截器已兜底生成 requestId
            return action.get().getData();
        }

        IdempotentRequest row = new IdempotentRequest();
        row.setRequestId(requestId);
        RequestContext.Context ctx = RequestContext.get();
        row.setRequestPath(ctx == null ? null : ctx.getPath());
        row.setRequestHash(requestHash);
        row.setStatus(STATUS_PROCESSING);
        row.setOperator(RequestContext.operator());

        try {
            idempotentMapper.insert(row);
        } catch (DuplicateKeyException e) {
            return replay(row.getRequestId(), requestHash);
        }

        IdempotentResult<?> result = action.get();

        row.setStatus(STATUS_DONE);
        row.setBusinessKey(result.getBusinessKey());
        row.setResponseSnapshot(toJson(result.getData()));
        row.setFinishTime(LocalDateTime.now());
        idempotentMapper.updateById(row);
        return result.getData();
    }

    private Object replay(String requestId, String requestHash) {
        IdempotentRequest existing = idempotentMapper.selectById(requestId);
        if (existing == null) {
            throw new IllegalStateException("幂等记录不存在：" + requestId);
        }
        if (requestHash != null && !requestHash.equals(existing.getRequestHash())) {
            throw new IllegalArgumentException("requestId 已被使用但请求内容不一致");
        }
        if (STATUS_PROCESSING.equals(existing.getStatus())) {
            throw new IllegalArgumentException("请求处理中，请勿重复提交");
        }
        return fromJson(existing.getResponseSnapshot());
    }

    /** 请求体摘要：对规范化 JSON 取 SHA-256。 */
    public String hash(Object body) {
        if (body == null) {
            return null;
        }
        return sha256(toJson(body));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private Object fromJson(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等快照反序列化失败", e);
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
