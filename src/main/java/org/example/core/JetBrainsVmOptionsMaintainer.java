package org.example.core;

import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.Level;
import org.example.tool.ContentFileDiffCompare;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.example.tool.LogFormatter.format;
import static org.example.tool.LogFormatter.formatDefault;

/**
 * 本类用于维护 JetBrains 系列 IDE 的 vmoptions 用户配置。
 * <ul>
 * <li>/vmoptions/specific/xx.vmoptions =>  特定产品的 vmoptions 参数；
 * <li>/vmoptions/general.vmoptions     =>  产品通用的 vmoptions 参数；
 * <li>/vmoptions/comment.vmoptions     =>  备注预设的 vmoptions 参数（例如：由 JetBrains Toolbox 自动生成的 JVM 配置）。
 * </ul>
 * 实测 IntelliJ IDEA 打开 vmoptions 文件时，大概率会出现弹出“未关联类型”窗口的问题，该问题大概是 IDEA 本身存在的 BUG 导致的。
 * <p>
 * 推荐解决办法是在“设置”-“编辑器”-“文件类型”中添加一个 VmOptions 文件类型，以匹配 *.vmoptions 的文件名模式。
 *
 * @author dylan
 * @version 2025/04/23 22:48
 */
@Log4j2
public class JetBrainsVmOptionsMaintainer {

    // *.用户目录、产品映射
    private static final Path WINDOWS_PATH = Path.of("AppData", "Roaming", "JetBrains");
    private static final Map<String, String> PRODUCT_MAP;

    static {
        // *.产品映射位于 resources/product.properties 文件中
        try (InputStream is = JetBrainsVmOptionsMaintainer.class.getResourceAsStream("/product.properties")) {
            Properties prop = new Properties();
            prop.load(is);

            PRODUCT_MAP = new HashMap<>(prop.size());
            prop.stringPropertyNames().forEach(key -> {
                PRODUCT_MAP.put(key, prop.getProperty(key));
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // *.JetBrains Toolbox 相关的 JVM 配置前缀
    private static final Set<String> TOOLBOX_PREFIXES = Set.of(
            "-Dide.managed.by.toolbox",
            "-Dtoolbox.notification.token",
            "-Dtoolbox.notification.portFile"
    );

    // *.预设 vmoptions 配置文件的字符串路径
    // !.注意 Class#getResourceAsStream() 不兼容 Path#toString()
    private static final String GENERAL_VM_OPTIONS = "/vmoptions/general.vmoptions";
    private static final String JETBRAINS_TOOLBOX_VM_COMMENT = "/vmoptions/comment.vmoptions";

    // *.备份文件目录、备份存档数量
    public static final String BACKUP_FILE = "${backupFile}";
    private static final Integer BACKUP_KEEP_COUNT = 5;

    // *.匹配 JetBrains Toolbox 相关 JVM 配置前缀
    private static final Pattern TOOLBOX_PREFIX_PATTERN = Pattern.compile("^.*(?==)");

    public static void run() {
        Path userJetBrainsPath = Path.of(
                System.getProperty("user.home"),    // *.系统用户主目录
                WINDOWS_PATH.toString()             // *.JetBrains 用户目录
        );

        // *.获取 JetBrains 用户目录下所有产品的 vmoptions 文件路径列表
        List<Path> productVmOptionsPaths = getVmOptionsPaths(userJetBrainsPath);

        // *.先获取所有的初始 JVM 参数（一般由 JetBrains Toolbox 自动生成）
        // *.以此为基础，进一步生成替换 vmoptions 文件的内容
        Map<Path, String> productVmOptionsPathsAndPresetVars = getPresetVars(productVmOptionsPaths);
        Map<Path, String> productVmOptionsPathsAndContents = getReplaceMap(productVmOptionsPathsAndPresetVars);

        // >.完成 vmoptions 文件内容的替换
        log.info("⚪[{}] 替换开始：[{}]",
                formatDefault(), "！");
        int[] count = new int[]{productVmOptionsPathsAndContents.size()};
        productVmOptionsPathsAndContents
                .forEach((productVmOptionsPath, contents) -> {
                    String productName = dirName(productVmOptionsPath.getParent());
                    String productVmOptionsName = dirName(productVmOptionsPath);

                    log.info("⚪[{}] 待替换数：{}",
                            formatDefault(), count[0]--);

                    try {
                        // !.备份文件可能出现 IOException 异常
                        vmOptionsBackup(productVmOptionsPath);

                        // *.差异比较，如果文件内容一致，则不需要进行替换操作
                        if (!ContentFileDiffCompare.compareWithDetails(productVmOptionsPath, contents).hasDiff()) {
                            log.info(
                                    "🔵[{}] 配置一致：{}",
                                    format(productName), productVmOptionsName);
                            log.info(
                                    "⚪[{}] 写入详情：{}",
                                    formatDefault(), "待替换文件无差异，无需覆盖。");
                            return;
                        }

                        // !.如果备份文件成功，则继续进行替换操作
                        // >.只要 productVmOptionsPath 的父目录存在，则不管 productVmOptionsPath 是否存在都可以进行创建
                        // >.否则会抛出 NoSuchFileException 异常
                        Files.writeString(productVmOptionsPath, contents, StandardCharsets.UTF_8, StandardOpenOption.CREATE);
                        log.info(
                                "🔵[{}] 写入成功：{}",
                                format(productName), productVmOptionsName);
                    } catch (IOException e) {
                        log.error(
                                "🔴[{}] 写入失败：{}",
                                format(productName), productVmOptionsName);
                        throw new RuntimeException(e);
                    }
                });
        log.info("⚪[{}] 替换完成：{}",
                formatDefault(), "如有写入错误，请仔细检查相关文件。");
    }

    /**
     * 获取所有 JetBrains 产品的 vmoptions 文件路径。
     *
     * @param userJetBrainsDir JetBrains 用户目录路径
     * @return vmoptions 文件列表
     */
    private static List<Path> getVmOptionsPaths(Path userJetBrainsDir) {
        // !.JetBrains 产品目录都会加上版本号，使用正则表达式过滤掉版本号
        // !.例如 IntelliJ IDEA 2024.3.5 的用户目录为 IntelliJIdea2024.3
        // !.过滤掉版本号之后可以从 PRODUCT_MAP 中获取对应产品的 vmoptions 文件名称
        List<Path> productVmOptionsPaths = new ArrayList<>();

        try (Stream<Path> productDirs = Files.list(userJetBrainsDir).filter(Files::isDirectory)) {
            productDirs
                    .forEach(userProductDir -> {
                        String productName = dirName(userProductDir).replaceAll("[\\d.]+", "");
                        String productVmOptionsName = PRODUCT_MAP.get(productName);

                        if (productVmOptionsName != null) {
                            Path productVmOptionsPath = userProductDir.resolve(productVmOptionsName);
                            productVmOptionsPaths.add(productVmOptionsPath);
                        }
                    });
        } catch (IOException e) {
            // >.异常从 Files#list(Path) 抛出
            // >.如果无法读取用户目录，则直接抛出异常以终止程序
            throw new RuntimeException(e);
        }
        return productVmOptionsPaths;
    }

    /**
     * 获取由 JetBrains Toolbox 生成的初始 JVM 参数。
     *
     * @param productVmOptionsPaths vmoptions 文件列表
     * @return vmoptions 文件及其初始的 JVM 参数
     */
    private static Map<Path, String> getPresetVars(List<Path> productVmOptionsPaths) {
        Map<Path, String> productVmOptionsPathsAndPresetVars = new HashMap<>();

        productVmOptionsPaths
                .forEach(productVmOptionsPath -> {
                    // *.以防不存在任何预设的 JVM 参数
                    productVmOptionsPathsAndPresetVars.put(productVmOptionsPath, "");

                    int count = TOOLBOX_PREFIXES.size();
                    try {
                        // *.倒序遍历，一般预设的 JVM 参数在文件末尾
                        List<String> contents = Files.readAllLines(productVmOptionsPath);
                        for (int i = contents.size() - 1; i >= 0; i--) {
                            // *.取出当前行并判断是否为 JetBrains Toolbox 生成的参数行
                            String line = contents.get(i);

                            Matcher matcher = TOOLBOX_PREFIX_PATTERN.matcher(line);
                            if (matcher.find() && TOOLBOX_PREFIXES.contains(matcher.group())) {
                                // *.将 vmoptions 文件路径（Path）和对应的初始 JetBrains Toolbox 参数关联
                                // >.因为存在预设 value 值，所以 BiFunction 内可以直接进行字符串拼接操作
                                productVmOptionsPathsAndPresetVars.compute(productVmOptionsPath,
                                        (key, savedLines) -> line + "\n" + savedLines);

                                // *.JetBrains Toolbox 的每条 JVM 参数只会出现一次
                                // *.一般来说只要出现一次，则必存在三条参数配置，提前剪断循环
                                if (--count == 0) break;
                            }
                        }
                    } catch (IOException e) {
                        // !.如果文件不存在，不需要抛出异常
                        // >.异常静默处理；因为 vmoptions 文件不存在，则可能是新安装的 JetBrains 产品
                        // >.同时，这不会影响其他 vmoptions 文件的读取，因为异常显然会终止整个程序的执行
                    }
                });
        return productVmOptionsPathsAndPresetVars;
    }

    /**
     * 获取替换的 vmoptions 文件内容。
     *
     * @param presetVars vmoptions 文件及其初始的 JVM 参数
     * @return vmoptions 文件及其替换内容
     */
    private static Map<Path, String> getReplaceMap(Map<Path, String> presetVars) {
        Map<Level, Integer> count = new HashMap<>();
        Map<Path, String> productVmOptionsPathsAndContents = new HashMap<>();

        Set<Path> productVmOptionsPaths = presetVars.keySet();

        productVmOptionsPaths
                .forEach(
                        productVmOptionsPath -> {
                            // *.PRODUCT_NAME_PATTERN 同样可以匹配 vmoptions 文件名
                            // >.例如 idea64.exe.vmoptions 能够匹配得到 idea 字符串，拼接可以得到 idea.vmoptions 文件名
                            String projectVmOptionsName = dirName(productVmOptionsPath).replaceAll("\\d{2}.*", "");
                            String produceName = dirName(productVmOptionsPath.getParent());

                            // !.获取对应 IDE 的独有 vmoptions 配置
                            // !.只有存在独有的 vmoptions 配置才会生成对应产品的 vmoptions 文件内容
                            String specific = "/vmoptions/special/" + projectVmOptionsName + ".vmoptions";

                            Class<JetBrainsVmOptionsMaintainer> clazz = JetBrainsVmOptionsMaintainer.class;
                            try (InputStream specialIs = clazz.getResourceAsStream(specific);
                                 InputStream generalIs = clazz.getResourceAsStream(GENERAL_VM_OPTIONS);
                                 InputStream commentIs = clazz.getResourceAsStream(JETBRAINS_TOOLBOX_VM_COMMENT)) {
                                if (specialIs != null && generalIs != null && commentIs != null) {
                                    String savedVar = presetVars.get(productVmOptionsPath);
                                    String newContent = combineVmOptionsContent(specialIs, generalIs, commentIs, savedVar);
                                    productVmOptionsPathsAndContents.put(productVmOptionsPath, newContent);

                                    int saved = count.getOrDefault(Level.INFO, 0);
                                    count.put(Level.INFO, saved + 1);
                                    log.info(
                                            "✅[{}] 存在配置：{}",
                                            format(produceName), specific);
                                } else {
                                    // *.resources 目录中不存在自定义的 vmoptions 配置时 InputStream 为 null 值
                                    int saved = count.getOrDefault(Level.DEBUG, 0);
                                    count.put(Level.DEBUG, saved + 1);
                                    log.debug(
                                            "❎[{}] 尚未配置：{}",
                                            format(produceName), specific);
                                }
                            } catch (IOException e) {
                                // *.异常会在 AutoCloseable 无法正常关闭时抛出
                                // *.或在 InputStream.readAllBytes() 时抛出，即 combineVmOptionsContent() 方法中抛出
                                int saved = count.getOrDefault(Level.ERROR, 0);
                                count.put(Level.ERROR, saved + 1);
                                log.error(
                                        "🔴[{}] 异常读取：{}",
                                        format(produceName), specific);
                                // >.异常静默处理
                                // >.当某个 vmoptions 文件处理失败时，其他 vmoptions 文件仍然可以正常尝试处理
                                // >.出现异常的产品其 vmoptions 文件就无法成功生成，对应的替换操作也无法完成
                            }
                        }
                );
        logSummary(count);
        return productVmOptionsPathsAndContents;
    }

    /**
     * 组合 vmoptions 文件内容。
     *
     * @param isSpecific 特定产品的 vmoptions 配置
     * @param isAll      所有产品共有的 vmoptions 配置
     * @param isToolbox  用于备注 Toolbox 生成的 vmoptions 配置
     * @param presetVar  初始的 JVM 参数（由 JetBrains Toolbox 自动生成）
     * @return 组合后的 vmoptions 文件内容
     * @throws IOException IO 异常
     */
    private static String combineVmOptionsContent(InputStream isSpecific, InputStream isAll,
                                                  InputStream isToolbox, String presetVar) throws IOException {
        String strNeeded = new String(isSpecific.readAllBytes()) + "\n" +
                new String(isAll.readAllBytes()) + "\n";

        // !.保险起见，将所有的 CRLF 换行符替换为 Unix 风格的 LF 换行符
        // !.因为 vmoptions 文件仅支持以 LF 作为换行符，不能存在 CRLF 换行符
        if (presetVar == null || presetVar.isEmpty()) {
            // >.避免不存在任何预设 JVM 参数的情况
            return strNeeded.replace("\r\n", "\n");
        }
        return (strNeeded + new String(isToolbox.readAllBytes()) + "\n" +
                presetVar).replace("\r\n", "\n");
    }

    /**
     * 输出 vmoptions 文件替换的统计信息。
     *
     * @param count 统计信息
     */
    private static void logSummary(Map<Level, Integer> count) {
        String separator = formatDefault();
        log.info("⚪[{}] 已配置数：{}", separator, count.getOrDefault(Level.INFO, 0));
        log.info("⚪[{}] 未配置数：{}", separator, count.getOrDefault(Level.DEBUG, 0));
        log.info("⚪[{}] 异常计数：{}", separator, count.getOrDefault(Level.ERROR, 0));
    }

    /**
     * 备份需被替换的 vmoptions 文件到项目的 ${backupFile} 目录下。
     *
     * @param productVmOptionsPath vmoptions 文件
     * @throws IOException IO 异常
     */
    @SuppressWarnings("LoggingSimilarMessage")
    private static void vmOptionsBackup(Path productVmOptionsPath) throws IOException {
        // !.JetBrains 产品是新安装的情况下，对应的 vmoptions 文件可能不存在
        if (Files.notExists(productVmOptionsPath)) return;

        Path projectPath = Path.of(System.getProperty("user.dir"));
        Path productPath = productVmOptionsPath.getParent();

        // !.生成备份文件后缀
        String dateSuffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));

        // !.确保备份目录存在
        Path backupPath = projectPath.resolve(BACKUP_FILE);
        if (!Files.exists(backupPath)) {
            try {
                Files.createDirectories(backupPath);
            } catch (IOException e) {
                log.error(
                        "🔴[{}] 创建失败：{}",
                        formatDefault(), "备份目录创建失败，重试程序或手动进行创建。");
                throw e;
            }
        }

        // !.确保产品目录存在
        String productName = dirName(productPath).replaceAll("[\\d.]+", "");
        Path productBackupPath = backupPath.resolve(productName);
        if (!Files.exists(productBackupPath)) {
            try {
                Files.createDirectories(productBackupPath);
            } catch (IOException e) {
                log.error(
                        "🔴[{}] 创建失败：{}",
                        formatDefault(), "产品目录创建失败，重试程序或手动进行创建。");
                throw e;
            }
        }

        // !.备份文件
        String vmOptionsFileName = dirName(productVmOptionsPath);
        Path vmOptionsBackupPath = productBackupPath.resolve(vmOptionsFileName + "_" + dateSuffix);
        try {
            Files.copy(productVmOptionsPath, vmOptionsBackupPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error(
                    "🔴[{}] 备份失败：{}",
                    formatDefault(), "文件备份失败，重试程序或手动进行备份。");
            throw e;
        }

        // !.输出日志
        String shortBackupPathStr = vmOptionsBackupPath.toString()
                .replace(projectPath + "\\", "")
                .replace("\\", "/");

        log.info(
                "🔵[{}] 文件备份：{}",
                format(productName), shortBackupPathStr);

        // !.清理旧备份
        cleanupBackups(productBackupPath);
    }

    /**
     * 清理过时的备份文件。
     * <p>
     * 注意，清理备份文件失败并不影响整个程序的主逻辑，因此方法的所有异常都可以静默处理。
     *
     * @param productBackupPath 产品备份目录
     */
    private static void cleanupBackups(Path productBackupPath) {
        try (Stream<Path> vmOptionsBackupPaths = Files.list(productBackupPath)) {
            List<Path> vmOptionsBackupPathsList = vmOptionsBackupPaths.toList();
            if (vmOptionsBackupPathsList.size() <= BACKUP_KEEP_COUNT) {
                return;
            }
            for (int i = 0; i < vmOptionsBackupPathsList.size() - BACKUP_KEEP_COUNT; i++) {
                Path deprecatedVmOptionsBackupPath = vmOptionsBackupPathsList.get(i);

                // !.删除过时的备份文件（路径是必然存在的不需要进行存在性判断）
                Files.delete(deprecatedVmOptionsBackupPath);

                Path projectPath = Path.of(System.getProperty("user.dir"));
                String shortBackupPathStr = deprecatedVmOptionsBackupPath.toString()
                        .replace(projectPath + "\\", "")
                        .replace("\\", "/");

                log.info(
                        "🔵[{}] 过时清理：{}",
                        format(productBackupPath), shortBackupPathStr);
            }
        } catch (IOException e) {
            // !.在文件已经备份的情况下，如果清理过时备份失败，可以不需要抛出异常
            // !.这类型的 IO 异常不影响程序的主要逻辑，后续修复代码或手动删除备份即可
            log.error(
                    "🔴[{}] 过时清理：{}",
                    format(productBackupPath), "备份清理失败，请手动删除过时备份。");
            // >.异常静默处理
        }
    }

    private static String dirName(Path path) {
        return path.getFileName().toString();
    }
}