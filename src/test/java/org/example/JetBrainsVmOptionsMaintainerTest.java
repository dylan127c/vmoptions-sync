package org.example;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

/**
 * @author dylan
 * @date 2025/4/26 03:21
 */
public class JetBrainsVmOptionsMaintainerTest {

    /**
     * 测试读取不存在的资源文件时，返回 null 而不是抛出异常。
     * <p>
     * 实际上 IOException 是在 try-with-resources 语句中抛出的，表示在关闭资源时发生了异常。
     */
    @Test
    void testResourcesRead() throws IOException {
        Class<JetBrainsVmOptionsMaintainerTest> clazz = JetBrainsVmOptionsMaintainerTest.class;
        try (InputStream is = clazz.getResourceAsStream("/not-exist.vmoptions")) {
            Assertions.assertNull(is);
        }
    }
}
