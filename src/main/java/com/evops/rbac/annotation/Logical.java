package com.evops.rbac.annotation;

/**
 * 多个权限码的组合逻辑。
 */
public enum Logical {
    /** 任一权限码命中即放行（一人多岗并集的默认语义）。 */
    ANY,
    /** 必须同时持有全部权限码（极少数场景预留）。 */
    ALL
}
