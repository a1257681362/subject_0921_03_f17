package com.evops.ticket.sensitive.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 敏感词条目视图。 */
@Data
public class SensitiveWordVO {
    private Long id;
    private String word;
    private String normalizedWord;
    private String level;
    private String category;
    private String status;
    private String remark;
    private String operator;
    private LocalDateTime updateTime;
}
