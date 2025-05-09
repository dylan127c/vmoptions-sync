package org.example.record;

/**
 * @author dylan
 * @version 2025/04/26 19:49
 */
public record DiffResult(boolean hasDiff, int totalLines, int diffCount, String message) {
}
