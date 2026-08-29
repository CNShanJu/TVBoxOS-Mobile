package com.github.tvbox.osc.util;

/**
 * 全局 Toast 捕获（release 空实现）：与 debug source set 的 {@link ToastCatcher} 同名同类，
 * release 编译替换 debug 版本——产物不含任何 hook 逻辑。
 */
public final class ToastCatcher {

    private ToastCatcher() {
    }

    /** release 空实现：无任何行为 */
    public static void install() {
        // no-op
    }
}
