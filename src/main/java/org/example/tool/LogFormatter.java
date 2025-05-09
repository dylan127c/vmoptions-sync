package org.example.tool;

import java.nio.file.Path;

/**
 * @author dylan
 * @version 2025/04/27 02:48
 */
public class LogFormatter {

    private static final String LOG_FORMAT = "%20s";
    private static final String LOG_SEPARATOR = "--------------------";

    /**
     * 默认的格式化日志输出。
     *
     * @return 格式化后的日志文本
     */
    public static String formatDefault() {
        return String.format(LOG_FORMAT, formatStr(LOG_SEPARATOR));
    }

    /**
     * 格式化日志输出。
     *
     * @param str 日志文本
     * @return 格式化后的日志文本
     */
    public static String format(String str) {
        return String.format(LOG_FORMAT, formatStr(str));
    }

    /**
     * 格式化日志输出。<p>
     * 将取用 Path 对象的文件名作为日志文本进行格式化。
     *
     * @param path 路径对象
     * @return 格式化后的日志文本
     */
    public static String format(Path path) {
        String pathName = path.getFileName().toString();
        return String.format(LOG_FORMAT, formatStr(pathName));
    }

    /**
     * 格式化字符串。<p>
     * 主要作用是在英文字符和数字之间插入空格，例如 IntelliJIdea2023.1 格式化后得到 IntelliJIdea 2023.1 字符串。
     *
     * @param str 待格式化的字符串
     * @return 格式化后的字符串
     */
    private static String formatStr(String str) {
        return str.toUpperCase().replaceAll("(?<=[a-zA-Z])(?=\\d)", " ");
    }
}
