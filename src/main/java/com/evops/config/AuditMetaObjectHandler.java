package com.evops.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.evops.common.web.RequestContext;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * BaseEntity 审计字段自动填充。
 * 取不到数字用户时填 0(system)，绝不留 null（update 时 null 会覆盖历史值）。
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        Long userId = RequestContext.auditUserId();
        strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
        strictInsertFill(metaObject, "createBy", Long.class, userId);
        strictInsertFill(metaObject, "updateBy", Long.class, userId);
        // 乐观锁初始版本：@Version 字段为 null 时 MP 不会拼版本条件，插入时必须显式置 0
        if (metaObject.hasSetter("version") && metaObject.getValue("version") == null) {
            metaObject.setValue("version", 0);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        strictUpdateFill(metaObject, "updateBy", Long.class, RequestContext.auditUserId());
    }
}
