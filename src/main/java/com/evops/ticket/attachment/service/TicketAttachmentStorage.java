package com.evops.ticket.attachment.service;

import com.evops.ticket.attachment.config.AttachmentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

/**
 * 附件二进制受管存储：文件落在 {@code evops.attachment.storage-dir} 下的扁平目录，
 * 存储名为服务端生成的 UUID（保留原始扩展名仅为运维辨识），与上传方原始文件名完全隔离，
 * 杜绝路径穿越；同名票多个附件不会互相覆盖。
 */
@Component
public class TicketAttachmentStorage {

    private static final Logger log = LoggerFactory.getLogger(TicketAttachmentStorage.class);

    private final AttachmentProperties properties;
    private Path root;

    public TicketAttachmentStorage(AttachmentProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() throws IOException {
        root = Paths.get(properties.getStorageDir()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        log.info("票附件存储目录：{}", root);
    }

    /**
     * 流式落盘：先写 {@code .tmp} 临时文件再原子改名，避免半成品文件被下载读到。
     *
     * @return 存储名（UUID + 扩展名）
     */
    public String store(InputStream in, String originalName) {
        String storageName = newStorageName(originalName);
        Path target = resolve(storageName);
        Path tmp = target.resolveSibling(storageName + ".tmp-" + UUID.randomUUID());
        try {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return storageName;
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new UncheckedIOException("附件保存失败", e);
        }
    }

    /** 打开附件输入流（调用方负责关闭）；文件丢失抛 {@link MissingAttachmentException}。 */
    public InputStream open(String storageName) {
        Path path = resolve(storageName);
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new MissingAttachmentException("附件文件缺失：" + storageName, e);
        }
    }

    public boolean exists(String storageName) {
        return Files.isRegularFile(resolve(storageName));
    }

    public long size(String storageName) {
        try {
            return Files.size(resolve(storageName));
        } catch (IOException e) {
            throw new UncheckedIOException("读取附件大小失败：" + storageName, e);
        }
    }

    public void deleteQuietly(String storageName) {
        if (!StringUtils.hasText(storageName)) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(storageName));
        } catch (IOException | RuntimeException e) {
            log.warn("附件物理文件删除失败：{}", storageName, e);
        }
    }

    /**
     * 拼存储文件绝对路径并做防穿越校验：规范化结果必须仍在根目录内。
     */
    private Path resolve(String storageName) {
        Path resolved = root.resolve(storageName).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("非法存储名");
        }
        return resolved;
    }

    private String newStorageName(String originalName) {
        String ext = "";
        if (originalName != null) {
            int dot = originalName.lastIndexOf('.');
            int slash = Math.max(originalName.lastIndexOf('/'), originalName.lastIndexOf('\\'));
            if (dot > slash && dot > 0 && dot < originalName.length() - 1) {
                String candidate = originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
                // 只保留字母数字扩展名，任何异常字符一律丢弃
                if (candidate.matches("[a-z0-9]{1,8}")) {
                    ext = "." + candidate;
                }
            }
        }
        return UUID.randomUUID().toString().replace("-", "") + ext;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 落盘失败清理，忽略
        }
    }
}
