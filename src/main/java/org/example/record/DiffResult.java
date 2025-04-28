package org.example.record;

/**
 * @author dylan
 * @date 2025/4/26 19:49
 */
public record DiffResult(boolean hasDiff, int totalLines, int diffCount, String message) {
}
