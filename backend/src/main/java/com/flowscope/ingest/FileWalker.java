package com.flowscope.ingest;

import java.nio.file.Path;
import java.util.List;

/** Recursively discovers analyzable source files under one or more directories. */
public interface FileWalker {

    /** Walk a single directory (its files are prefixed with the directory name). */
    WalkResult walk(Path sourceDirectory);

    /** Walk several module roots; each file's relative path is prefixed with its module. */
    WalkResult walkAll(List<ProjectRoots.Root> roots);
}
