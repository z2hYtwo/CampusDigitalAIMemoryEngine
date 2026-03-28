package com.campus.memory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import javax.print.*;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.Copies;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地硬件集成服务：连接物理扫描仪文件夹与本地打印机
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LocalHardwareIntegrationService {

    private final AssetService assetService;

    @Value("${hardware.scan.watch-path:E:/ScannerDrop}")
    private String watchPath;

    @Value("${hardware.print.default-printer:}")
    private String defaultPrinterName;

    private WatchService watchService;
    private ExecutorService watcherExecutor;
    private volatile boolean running = false;

    @PostConstruct
    public void init() {
        File dir = new File(watchPath);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (created) {
                log.info("已创建本地扫描监听目录: {}", watchPath);
            }
        }

        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            Path path = Paths.get(watchPath);
            path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
            
            this.running = true;
            this.watcherExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "scanner-watcher");
                t.setDaemon(true);
                return t;
            });

            this.watcherExecutor.submit(this::watchLoop);
            log.info("本地扫描仪监听已启动，目录: {}", watchPath);
        } catch (IOException e) {
            log.error("无法启动本地目录监听服务: {}", e.getMessage());
        }
    }

    private void watchLoop() {
        while (running) {
            try {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                        Path fileName = (Path) event.context();
                        Path fullPath = Paths.get(watchPath).resolve(fileName);
                        
                        // 稍微延迟一点处理，确保文件已完全写入
                        Thread.sleep(1000);
                        processNewFile(fullPath.toFile());
                    }
                }
                if (!key.reset()) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("本地扫描文件处理异常: {}", e.getMessage());
            }
        }
    }

    private void processNewFile(File file) {
        if (!file.exists() || file.isDirectory()) return;

        log.info("检测到新扫描文件: {}", file.getName());
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String contentType = probeContentType(file);
            
            // 调用 AssetService 模拟扫描仪上传 (deviceId 固定为 LOCAL-SCANNER)
            String result = assetService.processAsset(
                    bytes, 
                    file.getName(), 
                    contentType, 
                    "public", // 扫描件默认进入公共库
                    "system-scanner", 
                    "General", 
                    "official", 
                    "来自本地物理扫描仪的自动入库", 
                    null, 
                    assetService.new DefaultAssetProcessor()
            );
            
            log.info("本地扫描件入库结果: {}", result);
            
            // 处理完后将文件移动到 backup 目录，避免重复处理
            moveToBackup(file);
        } catch (Exception e) {
            log.error("处理扫描文件失败: {}", file.getName(), e);
        }
    }

    private String probeContentType(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    private void moveToBackup(File file) {
        File backupDir = new File(watchPath, "backup");
        if (!backupDir.exists()) backupDir.mkdirs();
        
        File target = new File(backupDir, file.getName());
        if (target.exists()) target.delete();
        
        if (file.renameTo(target)) {
            log.info("文件已归档至 backup 目录: {}", file.getName());
        }
    }

    /**
     * 调用本地物理打印机打印文档
     */
    public void printDocument(File file, int copies) {
        try {
            PrintService printer = findPrinter(defaultPrinterName);
            if (printer == null) {
                log.error("未找到指定的打印机: {}", defaultPrinterName);
                return;
            }

            DocPrintJob job = printer.createPrintJob();
            try (FileInputStream fis = new FileInputStream(file)) {
                DocFlavor flavor = DocFlavor.INPUT_STREAM.AUTOSENSE;
                Doc doc = new SimpleDoc(fis, flavor, null);
                
                PrintRequestAttributeSet attributes = new HashPrintRequestAttributeSet();
                attributes.add(new Copies(copies));
                
                job.print(doc, attributes);
                log.info("文档已发送至打印机: {}, 份数: {}", printer.getName(), copies);
            }
        } catch (Exception e) {
            log.error("本地打印失败: {}", e.getMessage());
        }
    }

    private PrintService findPrinter(String name) {
        PrintService[] services = PrintServiceLookup.lookupPrintServices(null, null);
        if (name == null || name.isBlank()) {
            return PrintServiceLookup.lookupDefaultPrintService();
        }
        for (PrintService service : services) {
            if (service.getName().equalsIgnoreCase(name)) {
                return service;
            }
        }
        return PrintServiceLookup.lookupDefaultPrintService();
    }

    @PreDestroy
    public void stop() {
        this.running = false;
        if (watcherExecutor != null) {
            watcherExecutor.shutdownNow();
        }
        try {
            if (watchService != null) {
                watchService.close();
            }
        } catch (IOException e) {
            log.error("关闭目录监听失败: {}", e.getMessage());
        }
    }
}
