package com.evops.ticket.agent.enums;

/** 入站/出站处理失败类型。 */
public enum ErrorType {
    /** XML 结构损坏，无法绑定对象 */
    PARSE_ERROR,
    /** 结构合法但字段不合规（缺必填、枚举非法等） */
    VALIDATION_ERROR,
    /** 业务处理失败（票不存在、状态机非法流转等） */
    BIZ_ERROR
}
