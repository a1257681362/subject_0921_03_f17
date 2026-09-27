package com.evops.ticket.mask.annotation;

import com.evops.ticket.mask.MaskType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记出系统时需要统一脱敏的敏感字段（检票单据：观众姓名、联系方式等）。
 *
 * <p>打码在 Jackson 序列化期统一完成：非白名单角色看到打码值；白名单场务角色
 * （由当前生效规则版本配置）看到完整值，同时逐字段写入明文查看留痕。
 *
 * <p>{@link #value()} 是字段声明的默认打码类型；规则版本可在不改代码的前提下
 * 覆盖「字段→打码类型」映射（规则表优先）。{@link #bizType()} / {@link #bizIdField()}
 * 用于留痕定位「看了哪条业务数据」：bizIdField 指向同一对象上的业务主键属性名
 * （如预约号 appointmentNo）。
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Sensitive {

    /** 字段默认打码类型（规则版本未收录该字段时使用）。 */
    MaskType value();

    /** 业务数据类型，留痕用，如 CheckinAppointment。 */
    String bizType();

    /** 同一对象上承载业务主键（预约号/票号等）的属性名，留痕定位用。 */
    String bizIdField() default "";
}
