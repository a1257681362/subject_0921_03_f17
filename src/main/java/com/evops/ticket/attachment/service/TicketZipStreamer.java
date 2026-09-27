package com.evops.ticket.attachment.service;

import com.evops.ticket.entity.TicketAttachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 整包 ZIP 流式生成：{@link ZipOutputStream} 直挂响应输出流，逐附件 8KB 缓冲拷贝，
 * 整包不落内存、不落临时盘；每个票号一个目录，根目录放 {@code 下载清单.txt}
 * （UTF-8 + BOM，Excel 打开中文不乱码；逐条列出票号 ↔ 包内文件）。
 *
 * 中文条目名用 UTF-8 写入并带 ZIP 的 EFS 语言标志位（JDK 在 UTF-8 字符集下对含非 ASCII
 * 的条目自动置位），Windows 10 资源管理器 / 主流解压工具均可正确还原中文名。
 *
 * 流式开始（响应头已提交）后无法再改回 JSON 错误响应，因此：
 * 票号数超限、无附件等可预判问题必须在开流前由 Service 拦截；
 * 单个物理文件缺失不中断整包，跳过该条目并在清单中标注"文件缺失"。
 */
@Component
public class TicketZipStreamer {

    private static final Logger log = LoggerFactory.getLogger(TicketZipStreamer.class);

    private static final int BUFFER_SIZE = 8 * 1024;
    private static final String MANIFEST_NAME = "下载清单.txt";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TicketAttachmentStorage storage;

    public TicketZipStreamer(TicketAttachmentStorage storage) {
        this.storage = storage;
    }

    /**
     * 将选中的附件流式写入 ZIP。方法返回前整包写完；调用方负责关闭外层流。
     * 在请求线程内同步执行（见控制器说明）。
     *
     * @param operator  打包操作人（由控制器在请求线程传入）
     * @param requestId 请求号（写清单用）
     */
    public void stream(TicketAttachmentService.ZipSelection selection, OutputStream rawOut,
                       String operator, String requestId) {
        List<String> missingFiles = new ArrayList<>();
        long writtenBytes = 0L;

        // BufferedOutputStream 减少小包写次数；ZipOutputStream 用 UTF-8 保证中文条目名
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(rawOut, BUFFER_SIZE * 4), StandardCharsets.UTF_8)) {

            // 同票号目录下可能有同名原始文件，按 "目录/文件名" 去重重命名
            Set<String> usedEntryNames = new HashSet<>();
            Map<String, List<String>> entriesByTicket = new HashMap<>();

            for (TicketAttachment att : selection.getAttachments()) {
                String dir = sanitizePathSegment(att.getTicketNo());
                String uniqueName = uniqueFileName(att.getFileName(), usedEntryNames, dir);
                String entryName = dir + "/" + uniqueName;

                if (!storage.exists(att.getStorageName())) {
                    // 元数据在、物理文件没了：不写条目，清单标注后继续，不影响整包其余文件
                    missingFiles.add(att.getTicketNo() + "/" + att.getFileName());
                    log.warn("票附件物理文件缺失，打包跳过：attachmentId={} ticketNo={} storageName={}",
                            att.getId(), att.getTicketNo(), att.getStorageName());
                    continue;
                }

                // 先打开文件再建 ZIP 条目：打开失败直接跳过，不留 0 字节空条目
                InputStream in;
                try {
                    in = storage.open(att.getStorageName());
                } catch (MissingAttachmentException | UncheckedIOException e) {
                    missingFiles.add(att.getTicketNo() + "/" + att.getFileName());
                    log.warn("票附件打开失败，打包跳过：ticketNo={} file={}",
                            att.getTicketNo(), att.getFileName(), e);
                    continue;
                }

                zip.putNextEntry(new ZipEntry(entryName));
                boolean complete = false;
                try (InputStream closeable = in) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int n;
                    while ((n = closeable.read(buffer)) != -1) {
                        zip.write(buffer, 0, n);
                        writtenBytes += n;
                    }
                    complete = true;
                } catch (IOException | UncheckedIOException e) {
                    // 读途中丢失：条目已建无法撤回，清单标注缺失；该条目数据可能不完整
                    log.warn("票附件读取中断，该条目可能不完整：ticketNo={} file={}",
                            att.getTicketNo(), att.getFileName(), e);
                }
                zip.closeEntry();
                if (complete) {
                    entriesByTicket.computeIfAbsent(att.getTicketNo(), k -> new ArrayList<>()).add(entryName);
                } else {
                    missingFiles.add(att.getTicketNo() + "/" + att.getFileName() + "（读取中断，条目可能不完整）");
                }
            }

            writeManifest(zip, selection, entriesByTicket, missingFiles, writtenBytes,
                    operator, requestId);
            zip.finish();
            zip.flush();
        } catch (IOException e) {
            // 响应可能已部分写出，只能中断；客户端拿到的是不完整 ZIP，按值班手册重试即可
            throw new UncheckedIOException("ZIP 流式生成中断", e);
        }
    }

    private void writeManifest(ZipOutputStream zip, TicketAttachmentService.ZipSelection selection,
                               Map<String, List<String>> entriesByTicket, List<String> missingFiles,
                               long writtenBytes, String operator, String requestId) throws IOException {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("演出票附件下载清单\r\n");
        sb.append("生成时间：").append(LocalDateTime.now().format(TIME_FMT)).append("\r\n");
        sb.append("操作人：").append(operator).append("\r\n");
        sb.append("请求号：").append(requestId).append("\r\n");
        sb.append("票号数量：").append(selection.getTicketNos().size())
                .append("，附件文件数：").append(selection.getAttachments().size()).append("\r\n");
        sb.append("实际写出：").append(writtenBytes).append(" 字节\r\n");
        sb.append("\r\n");
        sb.append("序号\t票号\t包内文件\r\n");

        int index = 1;
        for (String ticketNo : selection.getTicketNos()) {
            List<String> entries = entriesByTicket.get(ticketNo);
            if (entries == null || entries.isEmpty()) {
                continue;
            }
            for (String entry : entries) {
                sb.append(index++).append('\t').append(ticketNo).append('\t').append(entry).append("\r\n");
            }
        }

        if (!selection.getWithoutAttachments().isEmpty()) {
            sb.append("\r\n以下票号未检索到附件，请核实票号或先补传：\r\n");
            for (String ticketNo : selection.getWithoutAttachments()) {
                sb.append("- ").append(ticketNo).append("\r\n");
            }
        }
        if (!missingFiles.isEmpty()) {
            sb.append("\r\n以下附件元数据存在但物理文件缺失（未打入包内，请联系运维查存储目录）：\r\n");
            for (String name : missingFiles) {
                sb.append("- ").append(name).append("\r\n");
            }
        }

        zip.putNextEntry(new ZipEntry(MANIFEST_NAME));
        // UTF-8 BOM：让 Windows 版 Excel 直接以 UTF-8 识别，中文与制表符不错位
        zip.write(0xEF);
        zip.write(0xBB);
        zip.write(0xBF);
        zip.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /**
     * 目录段只放行票号/常见文件名字符，任何路径分隔符或穿越片段一律替换，
     * 保证 entry 不会逃出票号目录（双保险：上传时已 cleanPath）。
     */
    private String sanitizePathSegment(String segment) {
        String cleaned = segment.replace('\\', '_').replace('/', '_').replace("..", "__");
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    /** 同目录重名追加序号：报告.pdf -> 报告(2).pdf。 */
    private String uniqueFileName(String originalName, Set<String> used, String dir) {
        String base = sanitizePathSegment(originalName);
        String candidate = base;
        for (int i = 2; used.contains(dir + "/" + candidate); i++) {
            int dot = base.lastIndexOf('.');
            if (dot > 0) {
                candidate = base.substring(0, dot) + "(" + i + ")" + base.substring(dot);
            } else {
                candidate = base + "(" + i + ")";
            }
        }
        used.add(dir + "/" + candidate);
        return candidate;
    }
}
