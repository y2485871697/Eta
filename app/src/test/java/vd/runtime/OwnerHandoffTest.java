package vd.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * 纯 Java 覆盖 {@link OwnerHandoff#childNamePackage(String)}：把平台给出的子任务标识折叠成包名，
 * 无法安全解析时返回 null（身份未知），绝不猜测。不启动 owner，也不读取设备。
 */
public class OwnerHandoffTest {
    @Test public void flattenedComponentYieldsItsPackage() {
        assertEquals("com.bbk.launcher2",
                OwnerHandoff.childNamePackage("com.bbk.launcher2/.MainActivity"));
        assertEquals("com.bbk.launcher2",
                OwnerHandoff.childNamePackage("com.bbk.launcher2/com.bbk.launcher2.MainActivity"));
    }

    @Test public void barePackageIsAccepted() {
        assertEquals("com.example.app", OwnerHandoff.childNamePackage("com.example.app"));
    }

    @Test public void unreadableIdentitiesAreNeverGuessed() {
        assertNull(OwnerHandoff.childNamePackage(null));
        assertNull(OwnerHandoff.childNamePackage(""));
        assertNull(OwnerHandoff.childNamePackage("/.MainActivity"));
        assertNull(OwnerHandoff.childNamePackage("bad value"));
        assertNull(OwnerHandoff.childNamePackage("com.example\n.App"));
    }
}
