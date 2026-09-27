package com.evops.rbac.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口所需权限码声明。当前登录人启用岗位并集出的权限码命中任一即放行（多岗取并集）。
 *
 * <p>解析规则（见 RbacInterceptor）：
 * <ul>
 *   <li>方法上标注即整体覆盖类上的声明（不做类+方法拼接，避免合并后权限意外放大）；</li>
 *   <li>方法与类都未标注 = 仅要求登录，不做权限码校验；</li>
 *   <li>ADMIN 角色在代码层短路放行，不要求显式授权。</li>
 * </ul>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /** 需要的权限码；{@link Logical#ANY} 时任一命中即放行。 */
    String[] value();

    /** 多权限码的组合逻辑，默认 ANY（并集语义）。 */
    Logical logical() default Logical.ANY;
}
