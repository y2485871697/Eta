package vd.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 纯 Java 覆盖 {@link FocusWitness#focusSubstructureKnown(int, int[], String[])}：主屏焦点见证所用的
 * 保守、只读的子结构校验。它接受合法的桌面容器与单/多 Activity 根，并拒绝任何未知或非法结构。
 * 不接触设备。
 */
public class FocusWitnessTest {
    @Test public void desktopContainerWithNamedChildIsKnown() {
        // 真实设备形态：display 0 上的 home 根 #1 带启动器子任务 #2。
        assertTrue(FocusWitness.focusSubstructureKnown(1, new int[]{2},
                new String[]{"com.bbk.launcher2"}));
        assertTrue(FocusWitness.focusSubstructureKnown(1, new int[]{2},
                new String[]{"com.bbk.launcher2/.MainActivity"}));
    }

    @Test public void singleActivitySelfMarkerIsKnown() {
        assertTrue(FocusWitness.focusSubstructureKnown(16, new int[]{16}, null));
        assertTrue(FocusWitness.focusSubstructureKnown(16, new int[]{16}, new String[]{null}));
        assertTrue(FocusWitness.focusSubstructureKnown(16, new int[0], null));
        assertTrue(FocusWitness.focusSubstructureKnown(16, new int[0], new String[0]));
    }

    @Test public void multiActivityMarkerIsKnown() {
        assertTrue(FocusWitness.focusSubstructureKnown(16, new int[]{16, -1}, null));
        assertTrue(FocusWitness.focusSubstructureKnown(16, new int[]{-1, 16}, null));
    }

    @Test public void foreignChildWithoutANameIsUnknown() {
        assertFalse(FocusWitness.focusSubstructureKnown(1, new int[]{2}, null));
        assertFalse(FocusWitness.focusSubstructureKnown(1, new int[]{2}, new String[]{null}));
        assertFalse(FocusWitness.focusSubstructureKnown(1, new int[]{2}, new String[]{"unknown"}));
        assertFalse(FocusWitness.focusSubstructureKnown(1, new int[]{2}, new String[]{""}));
        assertFalse(FocusWitness.focusSubstructureKnown(1, new int[]{2}, new String[]{"bad name"}));
        // 存在但非平行的名称数组是矛盾证据。
        assertFalse(FocusWitness.focusSubstructureKnown(1, new int[]{2, 3},
                new String[]{"com.a.app"}));
        assertFalse(FocusWitness.focusSubstructureKnown(1, new int[]{2},
                new String[]{"com.a.app", "com.b.app"}));
    }

    @Test public void unreadableOrIllegalStructureIsRefused() {
        assertFalse(FocusWitness.focusSubstructureKnown(1, null, null));
        assertFalse(FocusWitness.focusSubstructureKnown(0, new int[]{1}, null));
        assertFalse(FocusWitness.focusSubstructureKnown(1, new int[]{2, 2},
                new String[]{"com.a.app", "com.a.app"})); // 重复 id
        assertFalse(FocusWitness.focusSubstructureKnown(1, new int[]{-2}, null)); // 未知标记
        assertFalse(FocusWitness.focusSubstructureKnown(1, new int[]{0}, null));  // 非任务零标记
    }

    @Test public void mixedMarkerAndNamedForeignChildIsKnown() {
        assertTrue(FocusWitness.focusSubstructureKnown(1, new int[]{-1, 2},
                new String[]{null, "com.bbk.launcher2"}));
    }
}
