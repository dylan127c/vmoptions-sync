package org.example.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * @author dylan
 * @version 2025/04/26 03:21
 */
public class JetBrainsVmOptionsMaintainerTest {

    /**
     * 测试读取不存在的资源文件时，返回 null 而不是抛出异常。<p>
     * 实际上 IOException 是在 try-with-resources 语句中抛出的，表示在关闭资源时发生了异常。
     */
    @Test
    void testResourcesRead() throws IOException {
        Class<JetBrainsVmOptionsMaintainerTest> clazz = JetBrainsVmOptionsMaintainerTest.class;
        try (InputStream is = clazz.getResourceAsStream("/non-exist.vmoptions")) {
            Assertions.assertNull(is);
        }
    }

    /**
     * 测试 {@link Files#isRegularFile(Path, LinkOption...)} 的功能是检查 {@link Path} 是一个常规文件。<p>
     * 所谓“常规文件”是指 {@link Path} 一个文件，而不是目录、链接或其他类型的文件。
     */
    @Test
    void testRegularFile() {
        String projectDir = System.getProperty("user.dir");

        // *.resources 目录中的 log4j2.xml 是一个常规文件
        Path logConfigPath = Path.of(projectDir, "src", "main", "resources", "log4j2.xml");
        Assertions.assertFalse(Files.isDirectory(logConfigPath));
        Assertions.assertTrue(Files.isRegularFile(logConfigPath));

        // *.resources 目录是一个目录而不是常规文件
        Path resourcesPath = Path.of(projectDir, "src", "main", "resources");
        Assertions.assertTrue(Files.isDirectory(resourcesPath));
        Assertions.assertFalse(Files.isRegularFile(resourcesPath));
    }

    /**
     * 测试 {@link Files#isRegularFile(Path, LinkOption...)} 能同时判断一个文件不存在。
     */
    @Test
    void testRegularFileWhenNonExist() {
        String projectDir = System.getProperty("user.dir");
        Path nonExistPath = Path.of(projectDir, "src", "main", "resources", "not-exist.xml");
        Assertions.assertFalse(Files.isRegularFile(nonExistPath));
    }

    /**
     * 测试 {@link Files#isDirectory(Path, LinkOption...)} 判断不存在的 {@link Path} 时返回 false 值。
     */
    @Test
    void testDirectoryWhenNonExist() {
        String projectDir = System.getProperty("user.dir");
        Path nonExistPath = Path.of(projectDir, "src", "main", "resources", "not-exist");
        Assertions.assertFalse(Files.isDirectory(nonExistPath));
    }
}