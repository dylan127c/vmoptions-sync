package org.example;

import org.example.core.JetBrainsLicenseSync;
import org.example.core.JetBrainsVmOptionsMaintainer;

/**
 * @author dylan
 * @date 2025/4/26 21:00
 */
public class Run {
    public static void main(String[] args) {
        JetBrainsVmOptionsMaintainer.run();
        JetBrainsLicenseSync.run();
    }
}
