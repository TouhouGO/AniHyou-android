package com.axiel7.anihyou.core.network.localization

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DefaultBundleFileOps].
 *
 * These use real temporary directories on purpose: the methods delegate straight to
 * java.io.File semantics, so mocking File would test nothing but the mock itself.
 */
class BundleFileOpsTest {

    private val fileOps = DefaultBundleFileOps()

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()

    // 1. isCanonicallyContainedIn — child directly under parent
    @Test
    fun isCanonicallyContainedIn_returnsTrueForDirectChild() {
        val root = tempDir("bundle_fileops_child")
        try {
            val bundleDir = File(root, "bundle")
            bundleDir.mkdirs()
            val child = File(bundleDir, "titles.json")
            child.writeText("{}")

            assertTrue(
                "A file directly inside the parent must be reported as contained",
                fileOps.isCanonicallyContainedIn(child, bundleDir)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    // 2. isCanonicallyContainedIn — classic path traversal out of the parent
    @Test
    fun isCanonicallyContainedIn_returnsFalseForPathTraversal() {
        val root = tempDir("bundle_fileops_traversal")
        try {
            val bundleDir = File(root, "bundle")
            bundleDir.mkdirs()
            val escapingChild = File(bundleDir, "../evil.txt")

            assertFalse(
                "A child that resolves outside the parent must be rejected",
                fileOps.isCanonicallyContainedIn(escapingChild, bundleDir)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    // 3. isCanonicallyContainedIn — sibling directory sharing the parent's name as a prefix.
    //    A naive "path startsWith parent path" check lets "<parent>.next_evil/f" through,
    //    which is the exact bypass exercised in LocalizationBundleTest.
    @Test
    fun isCanonicallyContainedIn_returnsFalseForSiblingPrefixBypass() {
        val root = tempDir("bundle_fileops_sibling")
        try {
            val bundleDir = File(root, "bundle")
            bundleDir.mkdirs()
            val siblingDir = File(root, "bundle.next_evil")
            siblingDir.mkdirs()
            val bypassChild = File(siblingDir, "evil.txt")
            bypassChild.writeText("evil payload")

            // Sanity check: this really is a prefix-based bypass, not just a missing file.
            assertTrue(
                "Precondition: the sibling path must carry the parent path as a raw prefix",
                bypassChild.absolutePath.startsWith(bundleDir.absolutePath)
            )
            assertFalse(
                "A sibling directory sharing the parent's name prefix must be rejected",
                fileOps.isCanonicallyContainedIn(bypassChild, bundleDir)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    // 4a. rename — success moves the file and removes it from the source location
    @Test
    fun rename_movesFileOnSuccess() {
        val root = tempDir("bundle_fileops_rename_ok")
        try {
            val source = File(root, "source.txt")
            source.writeText("payload")
            val target = File(root, "target.txt")

            assertTrue("rename should report success", fileOps.rename(source, target))
            assertFalse("source must no longer exist", source.exists())
            assertTrue("target must exist", target.exists())
            assertTrue("target must hold the moved content", target.readText() == "payload")
        } finally {
            root.deleteRecursively()
        }
    }

    // 4b. rename — failure when the target's parent directory does not exist.
    //     renameTo() does not create missing parents, so this returns false.
    @Test
    fun rename_returnsFalseWhenTargetParentIsMissing() {
        val root = tempDir("bundle_fileops_rename_fail")
        try {
            val source = File(root, "source.txt")
            source.writeText("payload")
            val target = File(File(root, "missing_dir"), "nested/target.txt")

            assertFalse(
                "rename into a non-existent directory must fail rather than silently succeed",
                fileOps.rename(source, target)
            )
            assertTrue("source must be left untouched after a failed rename", source.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    // 5. deleteRecursively — a missing file is reported as deleted (nothing to do).
    @Test
    fun deleteRecursively_returnsTrueForNonExistentFile() {
        val root = tempDir("bundle_fileops_delete_missing")
        try {
            val missing = File(File(root, "not_here"), "ghost.txt")

            assertTrue(
                "Deleting an already-absent file is a no-op and must not report failure",
                fileOps.deleteRecursively(missing)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    // 5b. deleteRecursively — a populated tree is removed entirely.
    @Test
    fun deleteRecursively_removesPopulatedTree() {
        val root = tempDir("bundle_fileops_delete_tree")
        try {
            val tree = File(root, "bundle")
            val nested = File(tree, "nested/deeper")
            nested.mkdirs()
            File(tree, "titles.json").writeText("{}")
            File(nested, "tags.json").writeText("{}")

            assertTrue("deleteRecursively should succeed", fileOps.deleteRecursively(tree))
            assertFalse("the deleted tree must be gone", tree.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    // 6. exists — true for a real file, false for a missing path and a dangling parent
    @Test
    fun exists_reflectsFileSystemState() {
        val root = tempDir("bundle_fileops_exists")
        try {
            val present = File(root, "present.json")
            present.writeText("{}")

            assertTrue("an existing file must be reported as existing", fileOps.exists(present))
            assertFalse(
                "a missing file must not be reported as existing",
                fileOps.exists(File(root, "absent.json"))
            )
            assertFalse(
                "a path under a missing directory must not be reported as existing",
                fileOps.exists(File(File(root, "no_such_dir"), "absent.json"))
            )
        } finally {
            root.deleteRecursively()
        }
    }

    // 6b. isDirectory — true for directories, false for files and missing paths
    @Test
    fun isDirectory_distinguishesDirectoriesFromFiles() {
        val root = tempDir("bundle_fileops_isdir")
        try {
            val dir = File(root, "bundle")
            dir.mkdirs()
            val file = File(root, "titles.json")
            file.writeText("{}")

            assertTrue("a real directory must be reported as a directory", fileOps.isDirectory(dir))
            assertFalse("a regular file must not be reported as a directory", fileOps.isDirectory(file))
            assertFalse(
                "a missing path must not be reported as a directory",
                fileOps.isDirectory(File(root, "missing_dir"))
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
