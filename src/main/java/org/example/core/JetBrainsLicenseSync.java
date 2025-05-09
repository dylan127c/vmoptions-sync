package org.example.core;

import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.example.tool.ContentFileDiffCompare.compareWithDiff;
import static org.example.tool.LogFormatter.format;
import static org.example.tool.LogFormatter.formatDefault;

/**
 * 本类能够备份所有已存在的 JetBrains 产品的许可证文件到项目目录下，并在需要时恢复到用户目录。<p>
 * 只要许可曾经存在且使用此类进行过备份，那么下次新安装 JetBrains 产品时只需要再执行此类即可恢复所有许可证。
 *
 * @author dylan
 * @date 2025/4/26 19:24
 */
@Log4j2
public class JetBrainsLicenseSync {

    // *.用户目录、产品映射
    private static final Path WINDOWS_PATH = Path.of("AppData", "Roaming", "JetBrains");
    private static final Map<String, String> PRODUCT_MAP;
    private static final Set<String> PRODUCT_SET;

    static {
        // *.产品映射位于 resources/product.properties 文件中
        try (InputStream is = JetBrainsVmOptionsMaintainer.class.getResourceAsStream("/product.properties")) {
            Properties prop = new Properties();
            prop.load(is);

            PRODUCT_MAP = new HashMap<>(prop.size());
            prop.stringPropertyNames().forEach(key -> {
                PRODUCT_MAP.put(key, prop.getProperty(key));
            });
            PRODUCT_SET = PRODUCT_MAP.keySet();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void run() {
        Path userJetBrainsPath = Path.of(
                System.getProperty("user.home"),    // *.系统用户主目录
                WINDOWS_PATH.toString()             // *.JetBrains 用户目录
        );

        backup(userJetBrainsPath);  // >.备份
        restore(userJetBrainsPath); // >.恢复
    }

    /**
     * 备份用户目录下的所有 *.key、*.license 文件到项目目录。
     *
     * @param userJetBrainsDir 完整的 JetBrains 用户目录
     */
    private static void backup(Path userJetBrainsDir) {
        try (Stream<Path> userLicenseProductDirs = Files.list(userJetBrainsDir)) {
            List<Path> userLicenseProductDirsList = userLicenseProductDirs
                    .filter(userLicenseProductDir -> {
                        String productName = dirName(userLicenseProductDir).replaceAll("[\\d.]+", "");
                        return PRODUCT_SET.contains(productName);
                    }).toList();

            for (Path userLicenseProductDir : userLicenseProductDirsList) {
                try (Stream<Path> userLicenseProductDirPaths = Files.list(userLicenseProductDir)) {
                    userLicenseProductDirPaths
                            .filter(Files::isRegularFile)
                            .filter(userLicenseProductDirPath -> {
                                String fileName = dirName(userLicenseProductDirPath);
                                return fileName.endsWith(".key") || fileName.endsWith(".license");
                            })
                            .forEach(userLicenseProductDirPath -> {
                                Path projectDir = Path.of(System.getProperty("user.dir"));
                                String productName = dirName(userLicenseProductDir).replaceAll("[\\d.]+", "");
                                Path projectLicenseProductDir = projectDir.resolve(
                                        Path.of("src", "main", "resources", "license", productName)
                                );
                                try {
                                    if (Files.notExists(projectLicenseProductDir)) {
                                        // !.复数形式的 createDirectories() 方法会创建所有不存在的父目录
                                        Files.createDirectories(projectLicenseProductDir);
                                    }

                                    Path projectLicenseProductDirPath =
                                            projectLicenseProductDir.resolve(userLicenseProductDirPath.getFileName());
                                    if (compareWithDiff(userLicenseProductDirPath, projectLicenseProductDirPath)) {
                                        Files.copy(userLicenseProductDirPath, projectLicenseProductDirPath);
                                    }
                                } catch (IOException e) {
                                    // >.备份文件必须保证成功，这里如果存在异常则中断程序
                                    throw new RuntimeException(e);
                                }
                            });
                }
            }
        } catch (IOException e) {
            // >.备份文件必须保证成功，这里如果存在异常则中断程序
            throw new RuntimeException(e);
        }
    }

    /**
     * 恢复项目目录下的所有 *.key、*.license 文件到用户目录。
     *
     * @param userJetBrainsDir 完整的 JetBrains 用户目录
     */
    private static void restore(Path userJetBrainsDir) {
        log.info(
                "⚪[{}] 许可恢复：[{}]",
                formatDefault(), "！");
        Path projectDir = Path.of(System.getProperty("user.dir"));
        Path projectLicenseDir = projectDir.resolve(Path.of("src", "main", "resources", "license"));

        // *.思路：先遍历项目内用于存储许可的产品目录，再遍历 JetBrains 用户目录下的产品目录（用户目录要遍历多次）
        // *.如果 JetBrains 用户目录下存在对应的产品目录，则尝试将项目保存的许可文件，复制到用户目录下对应产品的目录中
        try (Stream<Path> projectLicenseProductDirs =
                     Files.list(projectLicenseDir).filter(Files::isDirectory)) {

            // !.遍历项目许可目录下的所有产品目录，例如：license/GoLand、license/PyCharm 等
            // !.其中 projectLicenseProductDirs 即是这些产品目录的集合，包含：license/GoLand、license/PyCharm 等
            projectLicenseProductDirs.forEach(projectLicenseProductDir -> {

                try (Stream<Path> userLicenseProductDirs =
                             Files.list(userJetBrainsDir).filter(Files::isDirectory)) {
                    // !.遍历 JetBrains 用户目录下的所有产品目录，例如：GoLand2024.3、PyCharm2024.3 等
                    // !.其中 userLicenseProductDirs 即是这些产品目录的集合，包含：GoLand2024.3、PyCharm2024.3 等
                    userLicenseProductDirs.forEach(userLicenseProductDir -> {

                        // *.获取用户 JetBrains 目录下的产品目录名称和项目许可目录下的产品目录名称
                        String userLicenseProductDirName = dirName(userLicenseProductDir);
                        String projectLicenseProductDirName = dirName(projectLicenseProductDir);

                        // !.如果 JetBrains 用户目录下的产品目录名称和项目许可目录下的产品目录名称不一致，则跳过
                        if (!userLicenseProductDirName.startsWith(projectLicenseProductDirName)) return;

                        try {
                            // !.否则，尝试恢复项目许可目录下的产品目录到 JetBrains 用户目录下
                            Map<Status, Integer> statusMap = duplicateKeyAndLicense(
                                    projectLicenseProductDir,
                                    userLicenseProductDir
                            );
                            log.info(
                                    "⚪[{}] 恢复详情：文件[{}] => 成功[{}] | 跳过[{}] | 失败[{}]",
                                    formatDefault(),
                                    statusMap.values().stream().reduce(0, Integer::sum),
                                    statusMap.getOrDefault(Status.SUCCESS, 0),
                                    statusMap.getOrDefault(Status.SKIP, 0),
                                    statusMap.getOrDefault(Status.ERROR, 0)
                            );
                        } catch (IOException e) {
                            // >.这里抓取的是来自 duplicateKeyAndLicense() 方法的异常
                            // >.该异常源于 Files#list(Path) 方法，处理方式是显示日志但静默异常
                            // >.静默异常可以继续比对并尝试恢复下一个 JetBrains 用户目录下的产品许可
                            log.error(
                                    "🔴[{}] 恢复异常：{}",
                                    formatDefault(), e.getMessage());
                        }
                    });
                } catch (IOException e) {
                    // >.异常来源于 Files#list(Path) 方法，根源是无法列出 userJetBrainsDir 目录下的产品目录
                    // >.如果此目录无法被列出，那么后续的产品目录也无法被列出，因此这里不需要静默异常
                    log.error(
                            "🔴[{}] 目录异常：{}",
                            formatDefault(), e.getMessage());

                    // >.将 IOException 包装为运行时异常抛出，以中断程序
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException e) {
            // >.异常来源于 Files#list(Path) 方法，根源是无法列出 projectLicenseDir 目录下的产品目录
            // >.如果此目录无法被列出，那么后续的许可目录也无法被列出，因此这里不需要静默异常
            log.error(
                    "🔴[{}] 许可异常：{}",
                    formatDefault(), e.getMessage());

            // >.将 IOException 包装为运行时异常抛出，以中断程序
            throw new RuntimeException(e);
        }
    }

    /**
     * 复制 source 目录下的所有文件（非目录、链接等）到 target 目录下。
     *
     * @param source 源目录
     * @param target 目标目录
     */
    private static Map<Status, Integer> duplicateKeyAndLicense(Path source, Path target) throws IOException {
        Map<Status, Integer> statusMap = new HashMap<>();

        // *.注意 Files#list(Path) 方法内部的某些资源需求自动关闭
        // *.即使 try-with-resources 语句没有 catch 任何异常，仍不能省略 try-with-resources 语句
        try (Stream<Path> sourcePaths = Files.list(source).filter(Files::isRegularFile)) {
            sourcePaths.forEach(sourcePath -> {
                Path targetPath = target.resolve(sourcePath.getFileName());
                try {

                    // !.如果目标文件存在，同时和源文件内容一致（差异比对），则不进行复制
                    if (Files.exists(targetPath)
                            && !compareWithDiff(sourcePath, targetPath)) {
                        log.info(
                                "🔵[{}] 许可一致：{}",
                                format(target), dirName(sourcePath));
                        statusMap.put(Status.SKIP, statusMap.getOrDefault(Status.SKIP, 0) + 1);
                        return;
                    }

                    // !.由于源文件一定存在，则不管目标文件是否存在 Files#copy(..) 都可以进行复制
                    Files.copy(sourcePath, targetPath);
                    statusMap.put(Status.SUCCESS, statusMap.getOrDefault(Status.SUCCESS, 0) + 1);
                    log.info(
                            "🔵[{}] 写入完成：{}",
                            format(target), dirName(sourcePath));
                } catch (IOException e) {
                    statusMap.put(Status.ERROR, statusMap.getOrDefault(Status.ERROR, 0) + 1);
                    log.error(
                            "🔴[{}] 写入失败：{}",
                            format(target), dirName(sourcePath));
                    // >.静默处理异常，让下一个文件可以继续执行复制
                }
            });
        }
        return statusMap;
    }

    private static String dirName(Path path) {
        return path.getFileName().toString();
    }

    enum Status {
        SUCCESS, SKIP, ERROR
    }
}