package com.flowscope.ingest;

import java.util.List;

/**
 * The result of walking a source directory.
 *
 * @param files    discovered source files
 * @param warnings human-readable messages for skipped/unreadable entries
 */
public record WalkResult(
        List<SourceFile> files,
        List<String> warnings) {
}
