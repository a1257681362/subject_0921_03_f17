package com.evops.ticket.attachment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DataSizeUnit;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import javax.annotation.PostConstruct;

/**
 * 演出票附件参数（前缀 evops.attachment）。
 */
@Component
@ConfigurationProperties(prefix = "evops.attachment")
public class AttachmentProperties {

    /** 附件二进制受管存储目录（服务端生成 UUID 存储名，不使用原始文件名） */
    private String storageDir = "./data/attachments";

    /** 单次批量 ZIP 下载最多包含的票号数（场馆例会敲定：单包上限 50 份） */
    private int zipMaxTickets = 50;

    /** 单个附件大小上限，默认 20MB；同时受 Spring multipart 限制约束 */
    @DataSizeUnit(org.springframework.util.unit.DataUnit.MEGABYTES)
    private DataSize maxUploadSize = DataSize.ofMegabytes(20);

    @PostConstruct
    public void validate() {
        if (zipMaxTickets <= 0 || zipMaxTickets > 500) {
            throw new IllegalStateException("evops.attachment.zip-max-tickets 配置非法（1~500）");
        }
        if (maxUploadSize == null || maxUploadSize.toBytes() <= 0) {
            throw new IllegalStateException("evops.attachment.max-upload-size 配置非法");
        }
    }

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public int getZipMaxTickets() { return zipMaxTickets; }
    public void setZipMaxTickets(int zipMaxTickets) { this.zipMaxTickets = zipMaxTickets; }
    public DataSize getMaxUploadSize() { return maxUploadSize; }
    public void setMaxUploadSize(DataSize maxUploadSize) { this.maxUploadSize = maxUploadSize; }
}
