package com.github.tvbox.osc.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 多份订阅配置合并:订阅管理"导出"把勾选的订阅抓取下来后,合并成一份可直接当源用的配置文本。
 * 纯 JSON/字符串逻辑(不碰 Android、不联网、不做加解密),便于 JVM 单测。
 *
 * <p><b>合并策略</b>(所有冲突一律"先到先得",不覆盖已有内容):
 * <ul>
 *   <li>以第一份<b>能解析</b>的配置为底,它的 {@code spider}/{@code lives}/{@code wallpaper}/{@code ads}/{@code ijk}
 *       等一切原字段照搬 —— 这些字段跨配置合并没有意义,保留多份反而会互相打架;</li>
 *   <li>{@code sites}:逐份追加,按 {@code key} 去重(重复的只计数 Sk);</li>
 *   <li>{@code parses}:逐份追加,按 {@code url} 去重;</li>
 *   <li>{@code flags}:字符串并集;{@code rules}:整条内容去重后追加;</li>
 *   <li>后续配置的 {@code spider} 与底不同时<b>不合并</b>,只记一条冲突(站点 {@code api} 通常依赖各自那份 jar/JS,
 *       混在一起多半加载不出来,需要用户自己取舍)。</li>
 * </ul>
 * 加解密型配置(图片+base64 / AES)本类不参与解码,解析失败会进 {@link Result#skipped} 由调用方提示。
 */
public final class SubsConfigMerger {

    /** 一份待合并的配置:label 用于日志/提示,text 为空表示这份没抓到(也进 skipped) */
    public static final class Source {
        public final String label;
        public final String text;

        public Source(String label, String text) {
            this.label = label;
            this.text = text;
        }
    }

    /** 合并结果:json 为可直接落盘的配置文本;其余字段用于给用户/日志报个数 */
    public static final class Result {
        public final String json;
        /** 真正参与合并的配置份数 */
        public final int configs;
        /** 合并后 sites 总数 */
        public final int sites;
        /** 因 key 重复被丢掉的 sites 数 */
        public final int sitesSkipped;
        /** 合并后 parses 总数 */
        public final int parses;
        /** 因 url 重复被丢掉的 parses 数 */
        public final int parsesSkipped;
        /** spider 与底配置不一致(未合并)的配置名 */
        public final List<String> spiderConflicts;
        /** 抓取为空或解析失败的配置名 */
        public final List<String> skipped;

        Result(String json, int configs, int sites, int sitesSkipped, int parses, int parsesSkipped,
               List<String> spiderConflicts, List<String> skipped) {
            this.json = json;
            this.configs = configs;
            this.sites = sites;
            this.sitesSkipped = sitesSkipped;
            this.parses = parses;
            this.parsesSkipped = parsesSkipped;
            this.spiderConflicts = spiderConflicts;
            this.skipped = skipped;
        }

        /** 是否一个源都没合出来 */
        public boolean isEmpty() {
            return json == null || json.isEmpty() || sites <= 0 && parses <= 0;
        }
    }

    /** 复用实例(AGENTS:解析/序列化器不在热路径重复构建);格式化输出便于用户直接看 txt */
    private static final Gson PRETTY = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private SubsConfigMerger() {
    }

    /**
     * 合并若干份配置文本。
     *
     * @param sources 按用户勾选顺序传入(顺序决定"谁先到先得")
     */
    public static Result merge(List<Source> sources) {
        List<String> skipped = new ArrayList<>();
        List<String> spiderConflicts = new ArrayList<>();
        JsonObject base = null;
        int configs = 0;

        List<JsonArray> extraSites = new ArrayList<>();
        List<JsonArray> extraParses = new ArrayList<>();
        List<JsonArray> extraFlags = new ArrayList<>();
        List<JsonArray> extraRules = new ArrayList<>();
        int sitesSkipped = 0;
        int parsesSkipped = 0;

        if (sources != null) {
            for (Source s : sources) {
                if (s == null) continue;
                JsonObject obj = parseObject(s.text);
                if (obj == null) {
                    skipped.add(s.label);
                    continue;
                }
                if (base == null) {
                    base = obj;
                    configs = 1;
                    continue;
                }
                configs++;
                // spider 冲突:只记不改(不同蜘蛛的站点混用通常加载不出来)
                String spider = stringOrNull(obj, "spider");
                String baseSpider = stringOrNull(base, "spider");
                if (spider != null && !spider.isEmpty()
                        && baseSpider != null && !baseSpider.isEmpty()
                        && !spider.equals(baseSpider)) {
                    spiderConflicts.add(s.label);
                }
                JsonArray sites = arrayOrNull(obj, "sites");
                if (sites != null) extraSites.add(sites);
                JsonArray parses = arrayOrNull(obj, "parses");
                if (parses != null) extraParses.add(parses);
                JsonArray flags = arrayOrNull(obj, "flags");
                if (flags != null) extraFlags.add(flags);
                JsonArray rules = arrayOrNull(obj, "rules");
                if (rules != null) extraRules.add(rules);
            }
        }
        if (base == null) {
            return new Result("", 0, 0, 0, 0, 0, spiderConflicts, skipped);
        }

        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : base.entrySet()) {
            out.add(e.getKey(), e.getValue() == null ? null : e.getValue().deepCopy());
        }

        JsonArray baseSites = arrayOrNull(base, "sites");
        LinkedHashSet<String> siteKeys = new LinkedHashSet<>();
        JsonArray mergedSites = new JsonArray();
        if (baseSites != null) sitesSkipped += appendSites(baseSites, mergedSites, siteKeys);
        for (JsonArray extra : extraSites) {
            sitesSkipped += appendSites(extra, mergedSites, siteKeys);
        }
        if (mergedSites.size() > 0 || out.has("sites")) {
            out.add("sites", mergedSites);
        }

        JsonArray baseParses = arrayOrNull(base, "parses");
        LinkedHashSet<String> parseKeys = new LinkedHashSet<>();
        JsonArray mergedParses = new JsonArray();
        if (baseParses != null) parsesSkipped += appendParses(baseParses, mergedParses, parseKeys);
        for (JsonArray extra : extraParses) {
            parsesSkipped += appendParses(extra, mergedParses, parseKeys);
        }
        if (mergedParses.size() > 0 || out.has("parses")) {
            out.add("parses", mergedParses);
        }

        JsonArray mergedFlags = mergeStringArray(arrayOrNull(base, "flags"), extraFlags);
        if (mergedFlags.size() > 0 || out.has("flags")) out.add("flags", mergedFlags);

        LinkedHashSet<String> ruleKeys = new LinkedHashSet<>();
        JsonArray mergedRules = new JsonArray();
        appendRules(arrayOrNull(base, "rules"), mergedRules, ruleKeys);
        for (JsonArray extra : extraRules) appendRules(extra, mergedRules, ruleKeys);
        if (mergedRules.size() > 0 || out.has("rules")) out.add("rules", mergedRules);

        String json = PRETTY.toJson(out);
        // 正文不写注释头:导出文件要给第三方应用也能直接吃,严格 JSON 解析器遇到注释会整份读不出来
        return new Result(json, configs, mergedSites.size(), sitesSkipped,
                mergedParses.size(), parsesSkipped, spiderConflicts, skipped);
    }

    /** 追加 sites:按 key(缺失时按整条内容)去重;返回被跳过条数 */
    private static int appendSites(JsonArray from, JsonArray to, LinkedHashSet<String> seen) {
        int skipped = 0;
        for (JsonElement el : from) {
            String key = null;
            if (el != null && el.isJsonObject()) {
                key = stringOrNull(el.getAsJsonObject(), "key");
            }
            if (key == null || key.isEmpty()) key = el == null ? "" : el.toString();
            if (!seen.add(key)) {
                skipped++;
                continue;
            }
            to.add(el == null ? null : el.deepCopy());
        }
        return skipped;
    }

    /** 追加 parses:按 url(缺失时按整条内容)去重;返回被跳过条数 */
    private static int appendParses(JsonArray from, JsonArray to, LinkedHashSet<String> seen) {
        int skipped = 0;
        for (JsonElement el : from) {
            String key = null;
            if (el != null && el.isJsonObject()) {
                key = stringOrNull(el.getAsJsonObject(), "url");
            }
            if (key == null || key.isEmpty()) key = el == null ? "" : el.toString();
            if (!seen.add(key)) {
                skipped++;
                continue;
            }
            to.add(el == null ? null : el.deepCopy());
        }
        return skipped;
    }

    /** flags 并集(非字符串项原样保留一次) */
    private static JsonArray mergeStringArray(JsonArray base, List<JsonArray> extras) {
        JsonArray out = new JsonArray();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        appendStrings(base, out, seen);
        for (JsonArray extra : extras) appendStrings(extra, out, seen);
        return out;
    }

    private static void appendStrings(JsonArray from, JsonArray to, LinkedHashSet<String> seen) {
        if (from == null) return;
        for (JsonElement el : from) {
            if (el == null) continue;
            String key = el.isJsonPrimitive() ? el.getAsString() : el.toString();
            if (seen.add(key)) to.add(el.deepCopy());
        }
    }

    private static void appendRules(JsonArray from, JsonArray to, LinkedHashSet<String> seen) {
        if (from == null) return;
        for (JsonElement el : from) {
            if (el == null) continue;
            if (seen.add(el.toString())) to.add(el.deepCopy());
        }
    }

    /**
     * 解析配置文本:去掉 BOM 与开头 {@code //} 注释行(与 ApiConfig.FindResult 的容错口径一致),
     * 非 JSON 对象(网页/加密串/数组)一律视为不可用。
     */
    private static JsonObject parseObject(String text) {
        if (text == null) return null;
        String content = stripNoise(text);
        if (content.isEmpty()) return null;
        try {
            JsonElement el = JsonParser.parseString(content);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 去掉 BOM 与开头的 {@code //} 注释行 */
    static String stripNoise(String text) {
        if (text == null) return "";
        String content = text;
        if (!content.isEmpty() && content.charAt(0) == '\ufeff') content = content.substring(1);
        String trimmed = content.trim();
        while (trimmed.startsWith("//")) {
            int nl = trimmed.indexOf('\n');
            if (nl < 0) return "";
            trimmed = trimmed.substring(nl + 1).trim();
        }
        return trimmed;
    }

    private static JsonArray arrayOrNull(JsonObject obj, String key) {
        JsonElement el = obj == null ? null : obj.get(key);
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : null;
    }

    private static String stringOrNull(JsonObject obj, String key) {
        JsonElement el = obj == null ? null : obj.get(key);
        if (el == null || !el.isJsonPrimitive()) return null;
        try {
            return el.getAsString();
        } catch (Throwable t) {
            return null;
        }
    }
}
