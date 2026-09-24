package vd.runtime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 纯 Java 覆盖 {@link OwnerHandoff} 中不接触设备的采集辅助：
 * <ul>
 *   <li>{@link OwnerHandoff#childNamePackage(String)}：把平台给出的子任务标识折叠成包名，
 *       无法安全解析（含 AOSP 空任务占位 {@code "unknown"}、畸形组件）时返回 null，绝不猜测。</li>
 *   <li>{@link OwnerHandoff#parallelChildPackages(String[], int[])}：childTaskIds 与
 *       childTaskNames 的平行关联校验。</li>
 *   <li>{@link OwnerHandoff#effectiveChildNames(int, int[], String[])}：在只有自身/-1 标记、
 *       没有任何外来子 id 时，把缺失/不平行名称安全折叠成长度相同的 null 平行数组。</li>
 *   <li>{@link OwnerHandoff#completeChildIds(int[], int)}：子任务 id 集合的完整性与自引用校验。</li>
 * </ul>
 * 不启动 owner，也不读取设备。
 */
public class OwnerHandoffTest {
    @Test public void flattenedComponentYieldsItsPackage() {
        assertEquals("com.bbk.launcher2",
                OwnerHandoff.childNamePackage("com.bbk.launcher2/.MainActivity"));
        assertEquals("com.bbk.launcher2",
                OwnerHandoff.childNamePackage("com.bbk.launcher2/com.bbk.launcher2.MainActivity"));
        assertEquals("com.example.app",
                OwnerHandoff.childNamePackage("com.example.app/Sub$Nested"));
    }

    @Test public void barePackageIsAccepted() {
        assertEquals("com.example.app", OwnerHandoff.childNamePackage("com.example.app"));
        assertEquals("android", OwnerHandoff.childNamePackage("android"));
    }

    @Test public void unknownPlaceholderIsNeverAPackage() {
        // AOSP RootTaskInfo.childTaskNames fills "unknown" for a child task it cannot name.
        assertNull(OwnerHandoff.childNamePackage("unknown"));
        assertNull(OwnerHandoff.childNamePackage("Unknown"));
        assertNull(OwnerHandoff.childNamePackage("unknown/unknown"));
    }

    @Test public void unreadableIdentitiesAreNeverGuessed() {
        assertNull(OwnerHandoff.childNamePackage(null));
        assertNull(OwnerHandoff.childNamePackage(""));
        assertNull(OwnerHandoff.childNamePackage("/.MainActivity"));
        assertNull(OwnerHandoff.childNamePackage("bad value"));
        assertNull(OwnerHandoff.childNamePackage("com.example\n.App"));
    }

    @Test public void malformedComponentsAreRejected() {
        assertNull(OwnerHandoff.childNamePackage("com.example.app/"));      // missing class
        assertNull(OwnerHandoff.childNamePackage("com.example.app/bad class"));
        assertNull(OwnerHandoff.childNamePackage("com.example.app/a/b"));   // extra slash
        assertNull(OwnerHandoff.childNamePackage("com.example.app/."));     // empty short class
        assertNull(OwnerHandoff.childNamePackage("com..example"));          // empty segment
        assertNull(OwnerHandoff.childNamePackage("com.example."));          // trailing dot
        assertNull(OwnerHandoff.childNamePackage(".example.app"));          // leading dot
        assertNull(OwnerHandoff.childNamePackage("1com.example"));          // leading digit
        assertNull(OwnerHandoff.childNamePackage("com.example:remote"));    // process suffix
    }

    @Test public void parallelChildNamesAssociateByIndex() {
        assertArrayEquals(new String[]{"com.a.app", "com.b.app"},
                OwnerHandoff.parallelChildPackages(
                        new String[]{"com.a.app/.Main", "com.b.app"}, new int[]{7, 8}));
        // A child the platform cannot name stays null; the caller must fail closed.
        assertArrayEquals(new String[]{null, "com.b.app"},
                OwnerHandoff.parallelChildPackages(
                        new String[]{"unknown", "com.b.app"}, new int[]{7, 8}));
        // An empty id list is a complete, empty association.
        assertEquals(0, OwnerHandoff.parallelChildPackages(new String[0], new int[0]).length);
        assertEquals(0, OwnerHandoff.parallelChildPackages(null, new int[0]).length);
    }

    @Test public void nonParallelOrMissingChildNamesAreUnknown() {
        assertNull(OwnerHandoff.parallelChildPackages(null, new int[]{7}));
        assertNull(OwnerHandoff.parallelChildPackages(new String[]{"com.a.app"}, new int[]{7, 8}));
        assertNull(OwnerHandoff.parallelChildPackages(new String[]{"com.a.app", "com.b.app"},
                new int[]{7}));
        assertNull(OwnerHandoff.parallelChildPackages(new String[]{"com.a.app"}, null));
    }

    @Test public void emptyIdListPairsOnlyWithEmptyNames() {
        assertEquals(0, OwnerHandoff.parallelChildPackages(new String[0], new int[0]).length);
        assertEquals(0, OwnerHandoff.parallelChildPackages(null, new int[0]).length);
        // An empty id list alongside a non-empty name array is not a parallel association.
        assertNull(OwnerHandoff.parallelChildPackages(new String[]{"com.a.app"}, new int[0]));
    }

    @Test public void selfOnlyChildIdsSynthesizeNullNames() {
        // No foreign child id -> a same-length all-null array is faithful, never an invented package.
        assertArrayEquals(new String[]{null}, OwnerHandoff.effectiveChildNames(6, new int[]{6}, null));
        assertArrayEquals(new String[]{null, null},
                OwnerHandoff.effectiveChildNames(6, new int[]{-1, 6}, null));
        assertEquals(0, OwnerHandoff.effectiveChildNames(6, new int[0], null).length);
        // A foreign child id without a name stays unknown (never rescued by synthesis).
        assertNull(OwnerHandoff.effectiveChildNames(3, new int[]{4, 5}, null));
        assertNull(OwnerHandoff.effectiveChildNames(3, new int[]{4, 5}, new String[]{"com.a.app"}));
        // Parallel names pass through; the "unknown" placeholder still collapses to null.
        assertArrayEquals(new String[]{null, null},
                OwnerHandoff.effectiveChildNames(3, new int[]{4, 5},
                        new String[]{"unknown", "unknown"}));
        assertArrayEquals(new String[]{"com.a.app"},
                OwnerHandoff.effectiveChildNames(3, new int[]{4}, new String[]{"com.a.app"}));
    }

    @Test public void childIdsMustBeCompletePositiveUniqueAndForeign() {
        assertTrue(OwnerHandoff.completeChildIds(new int[]{4, 5}, 3));
        assertFalse(OwnerHandoff.completeChildIds(null, 3));
        assertFalse(OwnerHandoff.completeChildIds(new int[0], 3));
        assertFalse(OwnerHandoff.completeChildIds(new int[]{4, 4}, 3));   // duplicate
        assertFalse(OwnerHandoff.completeChildIds(new int[]{4, 3}, 3));   // self reference
        assertFalse(OwnerHandoff.completeChildIds(new int[]{-1, 4}, 3));  // non-task marker
        assertFalse(OwnerHandoff.completeChildIds(new int[]{0, 4}, 3));   // not positive
    }
}
