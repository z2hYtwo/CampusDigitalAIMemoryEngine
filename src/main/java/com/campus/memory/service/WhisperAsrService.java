package com.campus.memory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class WhisperAsrService {

    public record TranscriptionResult(String text, String language) {}
    private static final Map<Character, Character> TRADITIONAL_TO_SIMPLIFIED = createTraditionalToSimplifiedMap();

    private final ObjectMapper objectMapper;

    @Value("${asr.whisper.enabled:true}")
    private boolean enabled;

    @Value("${asr.whisper.api-key:${AI_API_KEY:}}")
    private String apiKey;

    @Value("${asr.whisper.mode:api}")
    private String mode;

    @Value("${asr.whisper.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${asr.whisper.model:whisper-1}")
    private String model;

    @Value("${asr.whisper.cli-command:whisper}")
    private String cliCommand;

    @Value("${asr.whisper.cli-model:base}")
    private String cliModel;

    @Value("${asr.whisper.cli-model-dir:}")
    private String cliModelDir;

    @Value("${asr.whisper.cli-device:}")
    private String cliDevice;

    @Value("${asr.whisper.cli-temp-dir:}")
    private String cliTempDir;

    @Value("${asr.whisper.cli-ffmpeg-dir:}")
    private String cliFfmpegDir;

    @Value("${asr.whisper.language:zh}")
    private String language;

    @Value("${asr.whisper.timeout-ms:30000}")
    private long timeoutMs;

    @Value("${asr.whisper.force-simplified-chinese:true}")
    private boolean forceSimplifiedChinese;

    @Value("${asr.whisper.simplified-prompt:请仅使用简体中文输出转写结果。}")
    private String simplifiedPrompt;

    @Value("${asr.whisper.send-initial-prompt:true}")
    private boolean sendInitialPrompt;

    @Value("${asr.whisper.normalize-traditional:true}")
    private boolean normalizeTraditional;

    private volatile HttpClient httpClient;

    public TranscriptionResult transcribe(MultipartFile file, String preferredLanguage) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("语音文件为空");
        }
        try {
            return transcribe(file.getBytes(), file.getOriginalFilename(), file.getContentType(), preferredLanguage);
        } catch (Exception e) {
            log.error("Whisper 转写失败: file={}", file.getOriginalFilename(), e);
            throw new RuntimeException("语音识别失败: " + e.getMessage(), e);
        }
    }

    public TranscriptionResult transcribe(byte[] fileBytes, String fileName, String contentType, String preferredLanguage) {
        if (!enabled) {
            throw new IllegalStateException("Whisper ASR 未启用");
        }
        if (fileBytes == null || fileBytes.length == 0) {
            throw new IllegalArgumentException("语音文件为空");
        }
        if (isCliMode()) {
            return transcribeByCli(fileBytes, fileName, preferredLanguage);
        }

        try {
            String endpoint = normalizeBaseUrl(baseUrl) + "/audio/transcriptions";
            String boundary = "----CDAMEWhisper" + UUID.randomUUID().toString().replace("-", "");
            String targetLanguage = resolveLanguage(preferredLanguage);
            byte[] requestBody = buildMultipartBody(fileBytes, fileName, contentType, boundary, targetLanguage, resolvePrompt(targetLanguage));

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody));
            if (apiKey != null && !apiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }
            HttpRequest request = requestBuilder.build();

            HttpResponse<String> response = getHttpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Whisper 调用失败: HTTP " + response.statusCode() + ", body=" + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String text = normalizeChineseText(root.path("text").asText("").trim());
            if (text.isBlank()) {
                throw new IllegalStateException("Whisper 返回空文本");
            }
            String detectedLanguage = root.path("language").asText("").trim();
            if (detectedLanguage.isBlank()) {
                detectedLanguage = resolveLanguage(preferredLanguage);
            }
            return new TranscriptionResult(text, detectedLanguage);
        } catch (Exception e) {
            log.error("Whisper 转写失败: file={}", fileName, e);
            throw new RuntimeException("语音识别失败: " + e.getMessage(), e);
        }
    }

    private TranscriptionResult transcribeByCli(byte[] fileBytes, String fileName, String preferredLanguage) {
        Path workDir = null;
        try {
            workDir = createCliWorkDir();
            String safeFileName = sanitizeFileName(fileName);
            Path inputFile = workDir.resolve(safeFileName);
            Files.write(inputFile, fileBytes);

            List<String> command = new ArrayList<>();
            command.add(cliCommand);
            command.add(inputFile.toString());
            command.add("--task");
            command.add("transcribe");
            command.add("--output_dir");
            command.add(workDir.toString());
            command.add("--output_format");
            command.add("txt");
            if (cliModel != null && !cliModel.isBlank()) {
                command.add("--model");
                command.add(cliModel.trim());
            }
            if (cliModelDir != null && !cliModelDir.isBlank()) {
                command.add("--model_dir");
                command.add(cliModelDir.trim());
            }
            if (cliDevice != null && !cliDevice.isBlank()) {
                command.add("--device");
                command.add(cliDevice.trim());
            }
            String targetLanguage = resolveLanguage(preferredLanguage);
            if (targetLanguage != null && !targetLanguage.isBlank()) {
                command.add("--language");
                command.add(targetLanguage);
            }
            String prompt = resolvePrompt(targetLanguage);
            if (prompt != null && !prompt.isBlank()) {
                command.add("--initial_prompt");
                command.add(prompt);
            }

            ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true);
            applyCliEnvironment(processBuilder);
            Process process = processBuilder.start();

            String stdout;
            try (InputStream stream = process.getInputStream()) {
                stdout = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("本地 Whisper 执行超时");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("本地 Whisper 执行失败: " + stdout);
            }

            Path outputFile = resolveCliOutputFile(workDir, safeFileName);
            if (!Files.exists(outputFile)) {
                if (looksLikeMissingFfmpeg(stdout)) {
                    throw new IllegalStateException("未找到 Whisper 输出文件，疑似缺少 ffmpeg。请安装 ffmpeg 并配置 ASR_CLI_FFMPEG_DIR 或系统 PATH。命令输出=" + clip(stdout, 1500));
                }
                throw new IllegalStateException("未找到 Whisper 输出文件: " + outputFile
                    + ", 目录内容=" + summarizeDirectory(workDir)
                    + ", 命令输出=" + clip(stdout, 1500));
            }
            String text = normalizeChineseText(Files.readString(outputFile, StandardCharsets.UTF_8).trim());
            if (text.isBlank()) {
                throw new IllegalStateException("Whisper 返回空文本, 文件=" + outputFile + ", 命令输出=" + clip(stdout, 1500));
            }
            return new TranscriptionResult(text, Objects.toString(targetLanguage, "auto"));
        } catch (Exception e) {
            log.error("本地 Whisper 转写失败: file={}", fileName, e);
            throw new RuntimeException("语音识别失败: " + e.getMessage(), e);
        } finally {
            deleteDirectoryQuietly(workDir);
        }
    }

    private byte[] buildMultipartBody(byte[] fileBytes, String fileName, String contentType, String boundary, String targetLanguage, String prompt) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writePart(output, boundary, "model", model);
        if (targetLanguage != null && !targetLanguage.isBlank()) {
            writePart(output, boundary, "language", targetLanguage);
        }
        if (prompt != null && !prompt.isBlank()) {
            writePart(output, boundary, "prompt", prompt);
        }
        writePart(output, boundary, "response_format", "verbose_json");

        String safeFileName = Objects.toString(fileName, "audio.wav");
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }

        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + safeFileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(fileBytes);
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
        output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }

    private void writePart(ByteArrayOutputStream output, String boundary, String name, String value) throws Exception {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "https://api.openai.com/v1";
        }
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }

    private String resolveLanguage(String preferredLanguage) {
        if (preferredLanguage != null && !preferredLanguage.isBlank()) {
            String normalizedPreferred = preferredLanguage.trim();
            if ("auto".equalsIgnoreCase(normalizedPreferred)) {
                return null;
            }
            return normalizedPreferred;
        }
        if (language != null && !language.isBlank()) {
            String normalizedDefault = language.trim();
            if ("auto".equalsIgnoreCase(normalizedDefault)) {
                return null;
            }
            return normalizedDefault;
        }
        return null;
    }

    private boolean isCliMode() {
        String normalized = Objects.toString(mode, "api").trim().toLowerCase(Locale.ROOT);
        return "cli".equals(normalized) || "local".equals(normalized) || "local-cli".equals(normalized);
    }

    private String sanitizeFileName(String fileName) {
        String source = (fileName == null || fileName.isBlank()) ? "audio.wav" : fileName.trim();
        String sanitized = source.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (sanitized.isBlank()) {
            return "audio.wav";
        }
        if (!sanitized.contains(".")) {
            return sanitized + ".wav";
        }
        return sanitized;
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return fileName;
        }
        return fileName.substring(0, dot);
    }

    private Path createCliWorkDir() throws Exception {
        if (cliTempDir != null && !cliTempDir.isBlank()) {
            Path baseDir = Path.of(cliTempDir.trim());
            Files.createDirectories(baseDir);
            return Files.createTempDirectory(baseDir, "cdame-whisper-");
        }
        return Files.createTempDirectory("cdame-whisper-");
    }

    private Path resolveCliOutputFile(Path workDir, String safeFileName) throws Exception {
        Path withExtension = workDir.resolve(safeFileName + ".txt");
        if (Files.exists(withExtension)) {
            return withExtension;
        }
        Path withoutExtension = workDir.resolve(stripExtension(safeFileName) + ".txt");
        if (Files.exists(withoutExtension)) {
            return withoutExtension;
        }
        try (var walk = Files.walk(workDir, 3)) {
            return walk
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                .sorted((a, b) -> b.getFileName().toString().compareToIgnoreCase(a.getFileName().toString()))
                .findFirst()
                .orElse(withoutExtension);
        }
    }

    private String summarizeDirectory(Path dir) {
        if (dir == null) {
            return "[]";
        }
        try (var walk = Files.walk(dir, 3)) {
            return walk
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .sorted()
                .limit(30)
                .toList()
                .toString();
        } catch (Exception e) {
            return "[目录读取失败: " + e.getMessage() + "]";
        }
    }

    private String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r", "").replace("\n", " | ").trim();
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(0, max) + "...";
    }

    private void applyCliEnvironment(ProcessBuilder processBuilder) {
        if (cliFfmpegDir == null || cliFfmpegDir.isBlank()) {
            return;
        }
        String ffmpegDir = cliFfmpegDir.trim();
        String existingPath = processBuilder.environment().getOrDefault("PATH", "");
        if (existingPath.isBlank()) {
            processBuilder.environment().put("PATH", ffmpegDir);
            return;
        }
        String separator = existingPath.contains(";") ? ";" : java.io.File.pathSeparator;
        processBuilder.environment().put("PATH", ffmpegDir + separator + existingPath);
    }

    private boolean looksLikeMissingFfmpeg(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return false;
        }
        String text = stdout.toLowerCase(Locale.ROOT);
        return (text.contains("ffmpeg") && text.contains("filenotfounderror"))
            || (text.contains("load_audio") && text.contains("_execute_child"))
            || text.contains("no such file or directory: 'ffmpeg'");
    }

    private String resolvePrompt(String targetLanguage) {
        if (!sendInitialPrompt) {
            return null;
        }
        if (!forceSimplifiedChinese) {
            return null;
        }
        if (targetLanguage == null || targetLanguage.isBlank()) {
            return null;
        }
        if (!"zh".equalsIgnoreCase(targetLanguage.trim())) {
            return null;
        }
        if (simplifiedPrompt == null || simplifiedPrompt.isBlank()) {
            return "请仅使用简体中文输出转写结果。";
        }
        return simplifiedPrompt.trim();
    }

    private HttpClient getHttpClient() {
        HttpClient current = httpClient;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (httpClient == null) {
                httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(timeoutMs))
                    .build();
            }
            return httpClient;
        }
    }

    private String normalizeChineseText(String text) {
        if (!normalizeTraditional || text == null || text.isBlank()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(TRADITIONAL_TO_SIMPLIFIED.getOrDefault(c, c));
        }
        return sb.toString();
    }

    private static Map<Character, Character> createTraditionalToSimplifiedMap() {
        Map<Character, Character> map = new LinkedHashMap<>();
        String traditional = "學體萬與專業東絲兩嚴喪個豐臨為麗舉麼義烏樂喬習鄉書買亂爭於虧雲亞產畝親褻嚲億僅從侖倉儀們價眾優夥會傘偉傳傷倫偽佇體餘傭俠侶偵側僑儈儕儘償儲兒兌黨兗蘭關興茲養獸內冊寫軍農冠冪冶凍淨凱擊鑿芻劃劉則剛創刪別刪剎剝劇勁勞勢勛勵勻匯匱區醫華協單賣盧鹵衛卻厭厲廠廳歷壓厭厲參雙發變疊隻臺葉號嘆嘯喚喪圍園圓圖團聖壓壘壞壟壢壩壯聲壹處備復夠夢奧奪奮妝婦媽嫻嬰學孫宮寬寶實寧審寫對尋導將專尋層屬岡島峽崗嶺嶼崢巒巔巖巢幣幫幹並廣莊慶廬庫應廟廢開異棄張彌彎彈當錄彥彫徑從復徠禦憂懷態恆戀惡惱悅懸惻惱慘慣慧戲戰戶拋挾擁擇掛採摯摳掄掙掛擾撫撲揮損換搶搖攝攜攤攪敗敘敵數斂斃斬斷無舊晉晝晉曆暈暫曉曬書會術朱朵機殺雜權條來楊極構槍槳樓標樣樹橋檔檢櫃檔欄歡歲歸殘殤殼毀毆氣氫漢湯洶潔淚濤淩淺淵減湊湧溫灣濕滅滯滾滿漁滲漸瀆潤澀潑潔瀉瀋瀏灘灣燈靈災爐煉點為爺牆獎獨獄獅獻瑪環現琺琿璣甌產畝畫當疇癡發皺盞監蓋盜盤眾睜睞瞞礦碼磚硯碩確禮禍禦稅稈穀穩窩窮竄竅競筆筍簡簽簾籃糾紀約紅紋納紐線練組紳細織終絃經紹紮結繞繪給絢統絲絕綏綁綠維綱網綴綸綺綻綽綜綾綿緊緒線締編緣縣緩縊縑縛縫總績繩繪繭繹繼纖缺罰罷羅羈聖聞聯聰聲聳職聶聽肅脅脈臉臍臘腦腳脫膚膠膽臟臨臺與興舉舊艙艦艱藝節芻苧范莊著葷薑蔥蔣藍蘇虛號蟲蝕衝補袞裡製複覺覽觀規覓視覺觸訃計訂訌討讓訓議訊記講訛許論訟設訪訣證評詛詞詠詩試誠話誕該詳語誤誒誚說誦請諸諾讀課誰調談諒論諭請諧諫諱諳諷謝謠謁謂謄謎謙講譚譜譯議護譽讀變豐貝貞負財責貢貧貨販貪購貯貫貳賤賁賄資賈賊賑賓賚賜賞賠賢賣賤質賬賭賴贈贊贏趕趙趨跡踐蹤躍車軋軍軌軒軟轉軸輕載較輔輛輝輥輪輯輸轄辭辯農邊遙遜遞遠遲適遷還邁運過達違遺遼鄧鄭鄰醜醬醫釀釋裡鐘鉤錢鉗鐵銅鋁銀銳錯鍋鍵鍾鎖鎮鏡鐮鐵鑒鑰長門開閃閉問間閒閣閱閻閹閾闊闌關隊陣陰陳陸陽險隱隸雜雞離難雲電霧霽靜靚響頁頂頃項順須頑頓頗領頸頻顆題顏類顧顫飄飆飛飢飯飲飼飾餅餌餓餘館饋饒馬馮馱馳駁駐駕騎驅驗驚驛驟髒髮鬥鬧鬱魚魯鮮鯉鯊鯨鰻鱷鳥鳴鴨鵝鷗鷹鹽麥麵黃黨黴齊齋齒齡龍龜";
        String simplified = "学体万与专业东丝两严丧个丰临为丽举么义乌乐乔习乡书买乱争于亏云亚产亩亲亵亸亿仅从仑仓仪们价众优伙会伞伟传伤伦伪伫体余佣侠侣侦侧侨侩侪尽偿储儿兑党兖兰关兴兹养兽内册写军农冠幂冶冻净凯击凿刍划刘则刚创删别删刹剥剧劲劳势勋励匀汇匮区医华协单卖卢卤卫却厌厉厂厅历压厌厉参双发变叠只台叶号叹啸唤丧围园圆图团圣压垒坏垄坜坝壮声壹处备复够梦奥夺奋妆妇妈娴婴学孙宫宽宝实宁审写对寻导将专寻层属冈岛峡岗岭屿峥峦巅岩巢币帮干并广庄庆庐库应庙废开异弃张弥弯弹当录彦雕径从复徕御忧怀态恒恋恶恼悦悬恻恼惨惯慧戏战户抛挟拥择挂采挚抠抡挣挂扰抚扑挥损换抢摇摄携摊搅败叙敌数敛毙斩断无旧晋昼晋历晕暂晓晒书会术朱朵机杀杂权条来杨极构枪桨楼标样树桥档检柜档栏欢岁归残殇壳毁殴气氢汉汤汹洁泪涛凌浅渊减凑涌温湾湿灭滞滚满渔渗渐渎润涩泼洁泻沈浏滩湾灯灵灾炉炼点为爷墙奖独狱狮献玛环现珐珲玑瓯产亩画当畴痴发皱盏监盖盗盘众睁睐瞒矿码砖砚硕确礼祸御税秆谷稳窝穷窜窍竞笔笋简签帘篮纠纪约红纹纳纽线练组绅细织终弦经绍扎结绕绘给绚统丝绝绥绑绿维纲网缀纶绮绽绰综绫绵紧绪线缔编缘县缓缢缣缚缝总绩绳绘茧绎继纤缺罚罢罗羁圣闻联聪声耸职聂听肃胁脉脸脐腊脑脚脱肤胶胆脏临台与兴举旧舱舰艰艺节刍苎范庄着荤姜葱蒋蓝苏虚号虫蚀冲补衮里制复觉览观规觅视觉触讣计订讧讨让训议讯记讲讹许论讼设访诀证评诅词咏诗试诚话诞该详语误诶诮说诵请诸诺读课谁调谈谅论谕请谐谏讳谙讽谢谣谒谓誊谜谦讲谭谱译议护誉读变丰贝贞负财责贡贫货贩贪购贮贯贰贱贲贿资贾贼赈宾赉赐赏赔贤卖贱质账赌赖赠赞赢赶赵趋迹践踪跃车轧军轨轩软转轴轻载较辅辆辉辊轮辑输辖辞辩农边遥逊递远迟适迁还迈运过达违遗辽邓郑邻丑酱医酿释里钟钩钱钳铁铜铝银锐错锅键钟锁镇镜镰铁鉴钥长门开闪闭问间闲阁阅阎阉阈阔阑关队阵阴陈陆阳险隐隶杂鸡离难云电雾霁静靓响页顶顷项顺须顽顿颇领颈频颗题颜类顾颤飘飙飞饥饭饮饲饰饼饵饿余馆馈饶马冯驮驰驳驻驾骑驱验惊驿骤脏发斗闹郁鱼鲁鲜鲤鲨鲸鳗鳄鸟鸣鸭鹅鸥鹰盐麦面黄党霉齐斋齿龄龙龟";
        int size = Math.min(traditional.length(), simplified.length());
        for (int i = 0; i < size; i++) {
            map.put(traditional.charAt(i), simplified.charAt(i));
        }
        return Map.copyOf(map);
    }

    private void deleteDirectoryQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
