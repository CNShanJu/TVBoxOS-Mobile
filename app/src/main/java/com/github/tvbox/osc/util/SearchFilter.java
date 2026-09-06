package com.github.tvbox.osc.util;

/**
 * 搜索结果行命中判定(自 FastSearchActivity.matchSearchResult 抽取的纯函数)。
 * <p>语义与旧实现逐字等价:
 * <ul>
 *   <li>name/查询词为 null 或空 → 不命中;</li>
 *   <li>查询词去首尾空白后按空白切词,要求 name 包含每个非空词(空 token 恒通过,
 *       与旧实现 name.contains("") 计数一致);</li>
 *   <li>仅空白组成的查询词 → 命中全部(旧实现 trim 后切词为空数组,计数相等)。</li>
 * </ul>
 */
public final class SearchFilter {

    private SearchFilter() {
    }

    public static boolean matches(String name, String searchTitle) {
        if (name == null || name.isEmpty() || searchTitle == null || searchTitle.isEmpty()) {
            return false;
        }
        String t = searchTitle.trim();
        String[] parts = t.split("\\s+");
        // 旧实现 dropLastWhile { it.isEmpty() }:仅去掉末尾空 token
        int n = parts.length;
        while (n > 0 && parts[n - 1].isEmpty()) {
            n--;
        }
        if (n == 0) {
            return true; // 纯空白查询词:命中全部(与旧实现一致)
        }
        for (int i = 0; i < n; i++) {
            if (parts[i].isEmpty()) continue; // 空 token 等价 name.contains("") 恒真
            if (!name.contains(parts[i])) {
                return false;
            }
        }
        return true;
    }
}
