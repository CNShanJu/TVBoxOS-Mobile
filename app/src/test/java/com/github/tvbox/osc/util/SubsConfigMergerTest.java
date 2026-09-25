package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 订阅导出-多份配置合并规则的 JVM 单测(纯 JSON 逻辑,不依赖 Android)。 */
public class SubsConfigMergerTest {

    private static SubsConfigMerger.Source src(String label, String json) {
        return new SubsConfigMerger.Source(label, json);
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String sitesOf(String json) {
        JsonArray arr = parse(json).getAsJsonArray("sites");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(arr.get(i).getAsJsonObject().get("key").getAsString());
        }
        return sb.toString();
    }

    private static String configOf(String spider, String... siteKeys) {
        StringBuilder sb = new StringBuilder("{\"spider\":\"").append(spider).append("\",\"sites\":[");
        for (int i = 0; i < siteKeys.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"key\":\"").append(siteKeys[i]).append("\",\"name\":\"").append(siteKeys[i])
                    .append("\",\"type\":1,\"api\":\"https://").append(siteKeys[i]).append("/api\"}");
        }
        return sb.append("]}").toString();
    }

    @Test
    public void sitesMergedInOrderAndDedupedByKey() {
        String a = configOf("jarA", "k1", "k2");
        String b = configOf("jarA", "k2", "k3");
        SubsConfigMerger.Result r = SubsConfigMerger.merge(Arrays.asList(src("A", a), src("B", b)));
        assertEquals(2, r.configs);
        assertEquals("k1,k2,k3", sitesOf(r.json));
        assertEquals(3, r.sites);
        assertEquals(1, r.sitesSkipped);   // B 的 k2 与 A 重复
        assertTrue(r.spiderConflicts.isEmpty());
        assertFalse(r.isEmpty());
    }

    @Test
    public void baseKeepsItsOtherFieldsAndLaterSpiderIsConflictOnly() {
        String a = "{\"spider\":\"jarA\",\"lives\":[{\"name\":\"live1\"}],\"wallpaper\":\"w.jpg\","
                + "\"sites\":[{\"key\":\"k1\"}]}";
        String b = "{\"spider\":\"jarB\",\"lives\":[{\"name\":\"live2\"}],"
                + "\"sites\":[{\"key\":\"k9\"}]}";
        SubsConfigMerger.Result r = SubsConfigMerger.merge(Arrays.asList(src("A", a), src("B", b)));
        JsonObject out = parse(r.json);
        // 底配置的 spider/lives/wallpaper 原样保留,不被后一份覆盖
        assertEquals("jarA", out.get("spider").getAsString());
        assertEquals("live1", out.getAsJsonArray("lives").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("w.jpg", out.get("wallpaper").getAsString());
        // 站点仍然合并进来,但 spider 冲突要报给用户
        assertEquals("k1,k9", sitesOf(r.json));
        assertEquals(1, r.spiderConflicts.size());
        assertEquals("B", r.spiderConflicts.get(0));
    }

    @Test
    public void parsesFlagsRulesDeduped() {
        String a = "{\"sites\":[{\"key\":\"k1\"}],\"parses\":[{\"name\":\"p1\",\"url\":\"u1\"}],"
                + "\"flags\":[\"qq\",\"youku\"],\"rules\":[{\"host\":\"a.com\",\"rule\":[\"r1\"]}]}";
        String b = "{\"sites\":[{\"key\":\"k2\"}],\"parses\":[{\"name\":\"p1dup\",\"url\":\"u1\"},{\"name\":\"p2\",\"url\":\"u2\"}],"
                + "\"flags\":[\"youku\",\"mgtv\"],\"rules\":[{\"host\":\"a.com\",\"rule\":[\"r1\"]},{\"host\":\"b.com\",\"rule\":[\"r2\"]}]}";
        SubsConfigMerger.Result r = SubsConfigMerger.merge(Arrays.asList(src("A", a), src("B", b)));
        JsonObject out = parse(r.json);
        assertEquals(2, out.getAsJsonArray("parses").size());
        assertEquals(1, r.parsesSkipped);
        assertEquals(3, out.getAsJsonArray("flags").size());
        assertEquals(2, out.getAsJsonArray("rules").size());
    }

    @Test
    public void unparsableSourcesAreSkippedNotFatal() {
        List<SubsConfigMerger.Source> sources = new ArrayList<>();
        sources.add(src("网页站", "<html><body>不是配置</body></html>"));
        sources.add(src("加密串", "2423abcdef$#..."));
        sources.add(src("空", null));
        sources.add(src("好配置", configOf("jarA", "k1")));
        SubsConfigMerger.Result r = SubsConfigMerger.merge(sources);
        assertEquals(1, r.configs);
        assertEquals("k1", sitesOf(r.json));
        assertEquals(3, r.skipped.size());
        assertTrue(r.skipped.contains("网页站"));
    }

    @Test
    public void bomAndLeadingCommentLinesTolerated() {
        String withNoise = "\ufeff// 订阅配置\n// 更新于 2026-09-25\n" + configOf("jarA", "k1");
        SubsConfigMerger.Result r = SubsConfigMerger.merge(Arrays.asList(src("A", withNoise)));
        assertEquals(1, r.configs);
        assertEquals("k1", sitesOf(r.json));
        assertTrue(r.skipped.isEmpty());
    }

    @Test
    public void allBadInputsYieldEmptyResult() {
        SubsConfigMerger.Result r = SubsConfigMerger.merge(Arrays.asList(
                src("A", "not json"), src("B", "")));
        assertTrue(r.isEmpty());
        assertEquals(0, r.configs);
        assertEquals(2, r.skipped.size());
        assertTrue(SubsConfigMerger.merge(null).isEmpty());
    }

    @Test
    public void sitesMissingInBaseStillCreated() {
        String a = "{\"spider\":\"jarA\"}";
        String b = configOf("jarA", "k1", "k2");
        SubsConfigMerger.Result r = SubsConfigMerger.merge(Arrays.asList(src("A", a), src("B", b)));
        assertEquals("k1,k2", sitesOf(r.json));
        assertEquals("jarA", parse(r.json).get("spider").getAsString());
    }
}
