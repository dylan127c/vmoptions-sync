package org.example;

import lombok.extern.log4j.Log4j2;
import org.apache.logging.log4j.Level;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 本类用于维护 JetBrains 系列 IDE 的 vmoptions 用户配置。
 * <ul>
 * <li>/vmoptions/specific/xx.vmoptions =>  特定产品的 vmoptions 参数；
 * <li>/vmoptions/general.vmoptions     =>  产品通用的 vmoptions 参数；
 * <li>/vmoptions/comment.vmoptions     =>  备注预设的 vmoptions 参数（例如：由 JetBrains Toolbox 自动生成的 JVM 配置）。
 * </ul>
 * 建议在 VSCode 中运行此程序，不建议在 IntelliJ IDEA 中运行，这可能导致 IDEA 本身的 vmoptions 无法正常打开。
 * <p>
 * 如果遇到 idea64.exe.vmoptions 无法正常打开的情况，选择“文件”-“使缓存失效...”后，勾选前两项以重启 IDE 并修复。
 * 
 * @author dylan
 * @date 2025/4/23 22:48
 */
@Log4j2
public class JetBrainsVmOptionsMaintainer {

    // *.Windows 系统下 JetBrains 产品的用户目录
    private static final Path WINDOWS_PATH = Path.of("AppData", "Roaming", "JetBrains");

    // *.Windows 系统下 JetBrains 产品的 vmoptions 文件名
    private static final Map<String, String> VM_OPTIONS_FILE_NAME = Map.of(
            "IntelliJIdea", "idea64.exe.vmoptions",
            "PyCharm", "pycharm64.exe.vmoptions",
            "GoLand", "goland64.exe.vmoptions",
            "WebStorm", "webstorm64.exe.vmoptions",
            "DataGrip", "datagrip64.exe.vmoptions",
            "Rider", "rider64.exe.vmoptions",
            "RustRover", "rustrover64.exe.vmoptions",
            "CLion", "clion64.exe.vmoptions",
            "PhpStorm", "phpstorm64.exe.vmoptions",
            "RubyMine", "rubymine64.exe.vmoptions"
    );

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

    // *.日志格式，及分隔符
    private static final String LOG_FORMAT = "%20s";
    private static final String LOG_SEPARATOR = "--------------------";

    // *.备份文件目录、备份存档数量
    public static final String BACKUP_FILE = "${backupFile}";
    private static final Integer BACKUP_KEEP_COUNT = 5;

    // *.匹配 JetBrains 产品名称，同时可应用于匹配 vmoptions 文件名
    private static final Pattern PRODUCT_NAME_PATTERN = Pattern.compile("^[a-zA-Z]*");
    // *.匹配 JetBrains Toolbox 相关 JVM 配置前缀
    private static final Pattern TOOLBOX_PREFIX_PATTERN = Pattern.compile("^.*(?==)");

    public static void main(String[] args) {
        Path userJetBrainsPath = Path.of(
                System.getProperty("user.home"),    // *.系统用户主目录
                WINDOWS_PATH.toString()             // *.JetBrains 用户目录
        );

        // *.获取 JetBrains 用户目录下所有产品的 vmoptions 文件路径列表
        List<Path> vmOptionsFiles = getVmOptionsPaths(userJetBrainsPath);

        // *.先获取所有的初始 JVM 参数（一般由 JetBrains Toolbox 自动生成）
        // *.以此为基础，进一步生成替换 vmoptions 文件的内容
        Map<Path, String> presetVar = getPresetVar(vmOptionsFiles);
        Map<Path, String> replaceMap = getReplaceMap(presetVar);

        // >.完成 vmoptions 文件内容的替换
        log.info("🌟[{}] 替换开始：{}",
                format(LOG_SEPARATOR), "！");
        int[] count = new int[]{replaceMap.size()};
        replaceMap.forEach((path, content) -> {
            String productName = path.getParent().getFileName().toString();
            String fileName = path.getFileName().toString();

            log.info("😑[{}] 待替换数：{}",
                    format(LOG_SEPARATOR), count[0]--);

            try {
                fileBackup(path);
                Files.writeString(path, content);
                log.info(
                        "🔵[{}] 写入成功：{}",
                        format(productName), fileName);
            } catch (IOException e) {
                log.error(
                        "❌[{}] 写入失败：{}",
                        format(productName), fileName);
                throw new RuntimeException(e);
            }
        });
        log.info("🌟[{}] 替换完成：{}",
                format(LOG_SEPARATOR), "如有写入失败，请仔细检查配置文件。");
    }

    /**
     * 获取所有 JetBrains 产品的 vmoptions 文件路径。
     *
     * @param jetBrainsPath JetBrains 用户目录路径
     * @return vmoptions 文件列表
     */
    private static List<Path> getVmOptionsPaths(Path jetBrainsPath) {
        // !.JetBrains 产品目录都会加上版本号，使用正则表达式过滤掉版本号
        // !.例如 IntelliJ IDEA 2024.3.5 的用户目录为 IntelliJIdea2024.3
        List<Path> paths = new ArrayList<>();

        try (Stream<Path> list = Files.list(jetBrainsPath)) {
            list.filter(Files::isDirectory)
                    .forEach(dir -> {
                        // *.获取产品目录的完整名称
                        String dirName = dir.getFileName().toString();
                        Matcher matcher = PRODUCT_NAME_PATTERN.matcher(dirName);
                        if (matcher.find()) {
                            // *.获取去除版本号后的产品名称
                            String productName = matcher.group();
                            // *.从预设的 Map 中获取产品对应的 vmoptions 文件名称
                            String vmOptionsFileName = VM_OPTIONS_FILE_NAME.get(productName);
                            // *.如果预设 Map 中存在产品对应的 vmoptions 文件名称
                            // *.则将 vmoptions 文件路径（Path）添加到列表中
                            if (vmOptionsFileName != null) {
                                paths.add(dir.resolve(vmOptionsFileName));
                            }
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return paths;
    }

    /**
     * 获取由 JetBrains Toolbox 生成的初始 JVM 参数。
     *
     * @param vmOptionsFiles vmoptions 文件列表
     * @return vmoptions 文件及其初始的 JVM 参数
     */
    private static Map<Path, String> getPresetVar(List<Path> vmOptionsFiles) {
        Map<Path, String> presetVar = new HashMap<>();

        vmOptionsFiles.forEach(path -> {
            // *.以防不存在任何预设的 JVM 参数
            presetVar.put(path, "");

            int count = TOOLBOX_PREFIXES.size();
            try {
                // *.倒序遍历，一般预设的 JVM 参数在文件末尾
                List<String> contents = Files.readAllLines(path);
                for (int i = contents.size() - 1; i >= 0; i--) {
                    // *.取出当前行并判断是否为 JetBrains Toolbox 生成的参数行
                    String line = contents.get(i);
                    Matcher matcher = TOOLBOX_PREFIX_PATTERN.matcher(line);
                    if (matcher.find() && TOOLBOX_PREFIXES.contains(matcher.group())) {
                        // *.将 vmoptions 文件路径（Path）和对应的初始 JetBrains Toolbox 参数关联
                        // >.因为存在预设 value 值，所以 BiFunction 内可以直接进行字符串拼接操作
                        presetVar.compute(path,
                                (key, savedLines) -> line + "\n" + savedLines);

                        // *.JetBrains Toolbox 的每条 JVM 参数只会出现一次
                        // *.且只要出现一次，则必存在三条参数配置，提前剪断循环
                        if (--count == 0) break;
                    }
                }
            } catch (IOException e) {
                // !.如果文件不存在，不需要抛出异常
                // >.throw new RuntimeException(e);
            }
        });
        return presetVar;
    }

    /**
     * 获取替换的 vmoptions 文件内容。
     *
     * @param presetVar vmoptions 文件及其初始的 JVM 参数
     * @return vmoptions 文件及其替换内容
     */
    private static Map<Path, String> getReplaceMap(Map<Path, String> presetVar) {
        Map<Level, Integer> count = new HashMap<>();
        Map<Path, String> replaceMap = new HashMap<>();

        presetVar.keySet().forEach(
                path -> {
                    // *.PRODUCT_NAME_PATTERN 同样可以匹配 vmoptions 文件名
                    // >.例如 idea64.exe.vmoptions 能够匹配得到 idea 字符串
                    String vmoptionsName = path.getFileName().toString();
                    Matcher matcher = PRODUCT_NAME_PATTERN.matcher(vmoptionsName);
                    if (matcher.find()) {
                        String produceName = path.getParent().getFileName().toString();

                        // !.获取对应 IDE 的独有 vmoptions 配置
                        // !.只有存在独有的 vmoptions 配置才会生成 vmoptions 文件
                        String specific = "/vmoptions/special/" + matcher.group() + ".vmoptions";

                        Class<JetBrainsVmOptionsMaintainer> clazz = JetBrainsVmOptionsMaintainer.class;
                        try (InputStream specialIs = clazz.getResourceAsStream(specific);
                             InputStream generalIs = clazz.getResourceAsStream(GENERAL_VM_OPTIONS);
                             InputStream commentIs = clazz.getResourceAsStream(JETBRAINS_TOOLBOX_VM_COMMENT)) {
                            if (specialIs != null && generalIs != null && commentIs != null) {
                                String savedVar = presetVar.get(path);
                                String newContent = combineVmOptionsContent(specialIs, generalIs, commentIs, savedVar);
                                replaceMap.put(path, newContent);

                                int saved = count.getOrDefault(Level.INFO, 0);
                                count.put(Level.INFO, saved + 1);
                                log.info(
                                        "✅[{}] 存在配置：{}",
                                        format(produceName), specific);
                            } else {
                                int saved = count.getOrDefault(Level.DEBUG, 0);
                                count.put(Level.DEBUG, saved + 1);
                                log.debug(
                                        "❎[{}] 尚未配置：{}",
                                        format(produceName), specific);
                            }
                        } catch (IOException e) {
                            int saved = count.getOrDefault(Level.ERROR, 0);
                            count.put(Level.ERROR, saved + 1);
                            log.error(
                                    "❌[{}] 异常读取：{}",
                                    format(produceName), specific);
                        }
                    }
                }
        );
        logSummary(count);
        return replaceMap;
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
        // !.避免不存在任何预设 JVM 参数的情况
        if (presetVar == null || presetVar.isEmpty()) {
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
        String separator = format(LOG_SEPARATOR);
        log.info("😀[{}] 已配置数：{}", separator, count.getOrDefault(Level.INFO, 0));
        log.info("🤔[{}] 未配置数：{}", separator, count.getOrDefault(Level.DEBUG, 0));
        log.info("🤨[{}] 异常计数：{}", separator, count.getOrDefault(Level.ERROR, 0));
    }

    /**
     * 备份需被替换的 vmoptions 文件到项目的 ${backupFile} 目录下。
     *
     * @param vmoptionsPath vmoptions 文件
     * @throws IOException IO 异常
     */
    private static void fileBackup(Path vmoptionsPath) throws IOException {
        if (Files.notExists(vmoptionsPath, LinkOption.NOFOLLOW_LINKS)) return;

        Path projectPath = Path.of(System.getProperty("user.dir"));
        Path productPath = vmoptionsPath.getParent();

        // !.生成备份文件后缀
        String dateSuffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));

        // !.确保备份目录存在
        Path backupPath = projectPath.resolve(BACKUP_FILE);
        if (!Files.exists(backupPath)) {
            Files.createDirectories(backupPath);
        }

        // !.确保产品目录存在
        String productName = productPath.getFileName().toString();
        Path productBackupPath = backupPath.resolve(productName);
        if (!Files.exists(productBackupPath)) {
            Files.createDirectories(productBackupPath);
        }

        // !.备份文件
        String vmoptionsFileName = vmoptionsPath.getFileName().toString();
        Path vmoptionsBackupPath = productBackupPath.resolve(vmoptionsFileName + "_" + dateSuffix);
        Files.copy(vmoptionsPath, vmoptionsBackupPath);

        // !.输出日志
        String shortBackupPathStr = vmoptionsBackupPath.toString().replace(projectPath + "\\", "").replace("\\", "/");
        log.info(
                "⚪[{}] 文件备份：{}",
                format(productName), shortBackupPathStr);

        // !.清理旧备份
        cleanupBackups(productBackupPath);
    }

    private static void cleanupBackups(Path productBackupPath) {
        String productName = productBackupPath.getFileName().toString();

        try (Stream<Path> vmoptionsBackupPathsStream = Files.list(productBackupPath)) {
            List<Path> vmoptionsBackupPaths = vmoptionsBackupPathsStream.toList();
            if (vmoptionsBackupPaths.size() <= BACKUP_KEEP_COUNT) {
                return;
            }
            for (int i = 0; i < vmoptionsBackupPaths.size() - BACKUP_KEEP_COUNT; i++) {
                Path deprecatedBackupPath = vmoptionsBackupPaths.get(i);

                Path projectPath = Path.of(System.getProperty("user.dir"));
                String shortBackupPathStr = deprecatedBackupPath.toString()
                        .replace(projectPath + "\\", "")
                        .replace("\\", "/");

                // !.删除过时的备份文件（路径是必然存在的不需要进行存在性判断）
                Files.delete(deprecatedBackupPath);
                log.info(
                        "⚫[{}] 过时清理：{}",
                        format(productName), shortBackupPathStr);
            }
        } catch (IOException e) {
            // !.在文件已经备份的情况下，如果清理过时备份失败，可以不需要抛出异常
            // !.这类型的 IO 异常不影响程序的主要逻辑，后续修复代码或手动删除备份即可
            log.error(
                    "❌[{}] 过时清理：{}",
                    format(productName), "备份清理失败，请手动删除过时备份。");
        }
    }

    /**
     * 格式化日志输出。
     *
     * @param text 日志文本
     * @return 格式化后的日志文本
     */
    private static String format(String text) {
        return String.format(LOG_FORMAT, text);
    }
}