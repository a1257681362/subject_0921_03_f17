package com.evops.ticket.sensitive.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 敏感词命中留痕视图（管理端审计回查）。 */
@Data
public class SensitiveHitLogVO {
    private Long id;
    private String scene;
    private String bizRef;
    private String fieldName;
    private Long wordId;
    private String word;
    private String level;
    private String matchedText;
    private Integer startCp;
    private Integer endCp;
    private String originalText;
    private String maskedText;
    /** BLOCKED / MASKED */
    private String action;
    private String operator;
    private String requestPath;
    private String requestId;
    private LocalDateTime hitTime;
}
