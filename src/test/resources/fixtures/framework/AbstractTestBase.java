package ca.bnc.lsist.core.base;

/** Fixture stand-in for the lsist-test-framework-core base class (parsed by FrameworkApiExtractor, not compiled). */
public abstract class AbstractTestBase {

    public enum WorldKey {
        RAW_RESPONSE, ACTUAL_RESPONSE, EXPECTED_RESPONSE, ROBOT_TOKEN, TEST_DATA, CLIENT_ID, CONTEXT
    }

    public static <E extends Enum<E>> void pushToTheWorld(E key, Object value) {
    }

    public static <T, E extends Enum<E>> T pullFromTheWorld(E key, Class<T> valueClass) {
        return null;
    }
}
