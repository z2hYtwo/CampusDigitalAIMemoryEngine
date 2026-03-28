# 物理交互终端：智能打印与反向入库技术方案

本方案详细描述了 CDAME 系统中“创意 4（反向入库）”与“创意 5（自动编年史）”的技术实现路径。

---

## 1. 创意 4：反向入库 (Scan-to-Knowledge)

### 1.1 业务流程
1.  **物理扫描**：用户在一体机扫描纸质文档/照片。
2.  **流式传输**：扫描件通过 WebDAV 或 FTP 协议进入 `asset-service`。
3.  **AI 预处理**：
    -   **OCR 提取**：利用系统内置的 Tess4J/Tika 提取文本。
    -   **图像分析**：利用大模型的多模态能力判断图片内容（人物、地点、年份）。
4.  **自动归档**：AI 根据提取到的信息，自动匹配 `sourceType`（校史、荣誉、学业）并关联相关实体。

### 1.2 关键技术
-   **Tess4J (OCR)**：处理手写笔记与印刷体识别。
-   **Multimodal LLM**：识别旧照片中的建筑与场景。
-   **Event-Driven**：利用文件监听机制（WatchService）实现“扫完即入库”。

---

## 2. 创意 5：自动编年史 (Autonomous Chronicle)

### 2.1 业务流程
1.  **AI 策展 (Curation)**：
    -   系统定时任务（如每周日晚）触发。
    -   `MemoryService` 检索过去 7 天新增的向量库内容。
    -   LLM 按照“校园要闻”、“荣誉之星”、“学业看板”三个板块编写周报。
2.  **排版合成**：
    -   将文字与相关缩略图合成 PDF 格式。
    -   自动生成一个带 Trace 溯源的二维码嵌入 PDF 底部。
3.  **打印调度**：
    -   通过 IPP 协议或云打印接口，将任务推送到指定打印节点。

### 2.2 关键技术
-   **RAG 摘要**：针对本周海量碎片的语义聚合。
-   **PDFBox / iText**：动态生成精美的 PDF 报纸样式。
-   **IPP (Internet Printing Protocol)**：与物理打印机的标准连接协议。

---

## 3. 扩展接口设计

### 3.1 扫描监听接口 (Scan Webhook)
`POST /api/physical/scan-callback` - 接收来自打印机/扫描仪的上传回调。

#### 物理终端具体测试步骤 (Physical Testing Steps)

为了验证真实物理终端与 CDAME 系统的协同工作，请按以下步骤操作：

**1. 硬件与网络准备 (Prerequisites)**
- **网络隔离**: 确保打印机/扫描仪与运行 CDAME `asset-service` 的服务器位于同一局域网（或打印机有公网访问权限）。
- **服务器 IP**: 获取服务器的局域网 IP（例如 `192.168.31.100`），确保 Spring Boot 端口（默认 8080）对局域网开放。
- **防火墙**: 在 Windows/Linux 服务器防火墙中放行 8080 端口。

**2. 打印机配置 (Printer Setup)**
大多数现代商用一体机（如 HP, Ricoh, Xerox）支持 "Scan to Web" 或 "HTTP Post" 功能：
- **目标 URL**: `http://192.168.31.100:8080/api/asset/physical/scan-callback`
- **请求方法**: `POST`
- **参数名称**: 文件字段必须命名为 `file`。
- **可选参数**: 在打印机界面配置 `deviceId` (例如 `LIB-SCAN-01`)。

*注：如果打印机仅支持 FTP/SMB，需在服务器运行 WatchService 脚本将文件转发至上述接口。*

**3. 物理扫描操作 (The Scan)**
- **放置文档**: 将一份**获奖证书**或**校史老报纸**放入进纸器。
- **选择扫描目的地**: 在打印机屏幕选择预设好的 "CDAME 智能归档" 按钮。
- **开始扫描**: 点击开始。打印机会将扫描产生的 PDF 或 JPG 文件实时推送到服务器。

**4. 结果验证 (Verification)**
- **后端日志**: 观察控制台，应看到 `接收到来自物理终端 [LIB-SCAN-01] 的扫描任务: honor_cert.jpg`。
- **AI 自动归档**: 几秒后，日志应显示 `资产已作为【荣誉】入库并向量化`。
- **前端查询**: 在 CDAME 检索界面输入“证书”或相关内容，应能搜索到刚刚扫描的文档，并看到 AI 自动生成的摘要。

**5. 模拟验证 (Simulated Test)**
在连接物理硬件前，可使用 `curl` 模拟打印机行为。

**对于 Windows PowerShell 用户：**
Windows 默认将 `curl` 别名为 `Invoke-WebRequest`，请显式调用 `curl.exe`：
```powershell
curl.exe -X POST -F "file=@E:\CampusData\学生公寓.png" `
         -F "deviceId=SIMULATOR-001" `
         http://localhost:8080/api/asset/physical/scan-callback
```

**对于 Linux/macOS 用户：**
```bash
curl -X POST -F "file=@/path/to/your/certificate.png" \
     -F "deviceId=SIMULATOR-001" \
     http://localhost:8080/api/asset/physical/scan-callback
```

*注意：文件路径前的 `@` 符号不可省略，它表示这是一个文件上传任务。*

---

## 4. 创意 5：分布式校园公告自动打印 (Campus Broadcast Print)

---

*本方案作为 CDAME 核心文档的补充，指导 P2/P3 阶段的物理交互功能开发。*
