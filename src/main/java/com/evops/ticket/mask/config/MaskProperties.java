package com.evops.ticket.mask.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 检票单据导出参数（前缀 evops.mask）。
 * 行数硬上限 5000：只能调小、不能调大（代码内再次封顶），超限在打开响应流之前拒绝。
 */
@Component
@ConfigurationProperties(prefix = "evops.mask")
public class MaskProperties {

    /** 单次导出预约单据最大行数（硬上限 5000） */
    private int exportMaxRows = 5000;

    public int getExportMaxRows() {
        return exportMaxRows;
    }

    public void setExportMaxRows(int exportMaxRows) {
        this.exportMaxRows = exportMaxRows;
    }

    /** 代码内硬封顶：配置不允许超过该值。 */
    public int effectiveExportMaxRows() {
        return Math.min(exportMaxRows, HARD_EXPORT_MAX_ROWS);
    }

    public static final int HARD_EXPORT_MAX_ROWS = 5000;
}
