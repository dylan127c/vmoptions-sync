package org.example.tool;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import org.example.record.DiffResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author dylan
 * @date 2025/4/26 19:44
 */
public class ContentFileDiffCompare {

    /**
     * 比较两个文件的内容，返回是否可以覆盖目标文件的布尔值。<p>
     * 方法返回 true 表示源文件内容与目标文件内容有差异；返回 false 表示源文件内容与目标文件内容一致。
     *
     * @param source 源文件
     * @param target 目标文件
     * @return 是否执行了覆盖操作
     * @throws IOException IO 异常
     */
    public static boolean compareWithDiff(Path source, Path target) throws IOException {
        // *.如果目标文件不存在，则表示可以进行覆盖操作
        if (!Files.exists(target)) {
            return true;
        }

        // *.读取文件内容为行列表
        List<String> sourceLines = Files.readAllLines(source, StandardCharsets.ISO_8859_1);
        List<String> targetLines = Files.readAllLines(target, StandardCharsets.ISO_8859_1);

        // *.计算差异
        Patch<String> patch = DiffUtils.diff(targetLines, sourceLines);
        List<AbstractDelta<String>> deltas = patch.getDeltas();

        // !.无差异时 delta.isEmpty() 为 true 值，此方法需要返回 false 表示文件无差异（无需覆盖）
        // !.反之 delta.isEmpty() 为 false 值，此方法需要返回 true 表示文件有差异（需要覆盖）
        return !deltas.isEmpty();
    }

    /**
     * 比较文件与字符串内容，返回是否可以覆盖文件的布尔值。<p>
     * 方法返回 true 表示源文件内容与目标文件内容有差异；返回 false 表示源文件内容与目标文件内容一致。
     *
     * @param filePath   文件路径
     * @param newContent 新内容字符串
     * @return 是否执行了覆盖操作
     * @throws IOException IO 异常
     */
    public static boolean compareWithDiff(Path filePath, String newContent) throws IOException {
        // *.将字符串内容转换为行列表
        List<String> contentLines;
        try (BufferedReader reader = new BufferedReader(new StringReader(newContent))) {
            contentLines = reader.lines().collect(Collectors.toList());
        }

        // *.文件不存在，但父目录存在，那么使用 Files.writeString(filePath, newContent) 没问题
        // >.此时覆盖可以进行，则返回 true 表示文件有差异（需要覆盖）
        if (!Files.exists(filePath)) {
            return true;
        }

        // *.读取文件内容为行列表
        List<String> fileLines = Files.readAllLines(filePath);

        // *.计算差异
        Patch<String> patch = DiffUtils.diff(fileLines, contentLines);
        List<AbstractDelta<String>> deltas = patch.getDeltas();

        // !.无差异时 delta.isEmpty() 为 true 值，此方法需要返回 false 表示文件无差异（无需覆盖）
        // !.反之 delta.isEmpty() 为 false 值，此方法需要返回 true 表示文件有差异（需要覆盖）
        return !deltas.isEmpty();
    }

    /**
     * 比较文件与字符串内容，返回差异信息。
     *
     * @param filePath   文件路径
     * @param newContent 新内容字符串
     * @return 差异结果
     * @throws IOException IO 异常
     */
    public static DiffResult compareWithDetails(Path filePath, String newContent) throws IOException {
        List<String> contentLines;
        try (BufferedReader reader = new BufferedReader(new StringReader(newContent))) {
            contentLines = reader.lines().collect(Collectors.toList());
        }

        // *.文件不存在，但父目录存在，那么使用 Files.writeString(filePath, newContent) 没问题
        // >.此时覆盖可以进行，则返回 true 表示文件有差异（需要覆盖）
        if (!Files.exists(filePath)) {
            return new DiffResult(true, contentLines.size(), 1, "文件不存在，允许覆盖");
        }

        List<String> fileLines = Files.readAllLines(filePath);
        Patch<String> patch = DiffUtils.diff(fileLines, contentLines);
        List<AbstractDelta<String>> deltas = patch.getDeltas();
        boolean hasDiff = !deltas.isEmpty();

        return new DiffResult(
                hasDiff,
                contentLines.size(),
                deltas.size(),
                hasDiff ? "共有 " + deltas.size() + " 处差异，允许覆盖" : "文件内容一致，无需覆盖"
        );
    }
}