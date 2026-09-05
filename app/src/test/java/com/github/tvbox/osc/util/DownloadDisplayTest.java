package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.github.tvbox.osc.bean.VideoInfo;
import com.github.tvbox.osc.download.ArchiveItem;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** DownloadDisplay(下载完成列表展示纯工具)单测:格式化/剧集名/清晰度/指纹/删除 */
public class DownloadDisplayTest {

    private static ArchiveItem item(String episodeName, String episodeId) {
        ArchiveItem it = new ArchiveItem();
        it.episodeName = episodeName;
        it.episodeId = episodeId;
        return it;
    }

    @Test
    public void formatSize_units() {
        assertEquals("0KB", DownloadDisplay.formatSize(0));
        assertEquals("1KB", DownloadDisplay.formatSize(1024));
        assertEquals("1023KB", DownloadDisplay.formatSize(1024 * 1024 - 1));
        assertEquals("1MB", DownloadDisplay.formatSize(1024 * 1024));
        assertEquals("1023MB", DownloadDisplay.formatSize(1024L * 1024 * 1024 - 1));
        assertEquals("1.00GB", DownloadDisplay.formatSize(1024L * 1024 * 1024));
    }

    @Test
    public void formatSpeed_units() {
        assertEquals("512B/s", DownloadDisplay.formatSpeed(512));
        assertEquals("1KB/s", DownloadDisplay.formatSpeed(1024));
        assertEquals("1024KB/s", DownloadDisplay.formatSpeed(1024 * 1024 - 1));
        assertEquals("1.0MB/s", DownloadDisplay.formatSpeed(1024 * 1024));
        assertEquals("2.5MB/s", DownloadDisplay.formatSpeed((long) (2.5 * 1024 * 1024)));
    }

    @Test
    public void lastSegment_splitsOnPipe() {
        assertEquals("3", DownloadDisplay.lastSegment("src|vod|3"));
        assertNull(DownloadDisplay.lastSegment("no-pipe"));
        assertNull(DownloadDisplay.lastSegment(""));
        assertNull(DownloadDisplay.lastSegment(null));
    }

    @Test
    public void episodeTitleOf_prefersEpisodeNameAndStripsResolution() {
        assertEquals("第1集", DownloadDisplay.episodeTitleOf(item("第1集_720P", "s|v|1"), "any.mp4"));
        assertEquals("第2集", DownloadDisplay.episodeTitleOf(item(null, "s|v|2"), "any.mp4"));
        // 无 episodeName/episodeId 数字段:回退文件名(去扩展名)
        assertEquals("movie", DownloadDisplay.episodeTitleOf(item(null, null), "movie.mkv"));
        // 保留非清晰度文本
        assertEquals("正片", DownloadDisplay.episodeTitleOf(item("正片_4K", null), "any.mp4"));
    }

    @Test
    public void resolutionOf_parsesLastResolutionSegment() {
        assertEquals("720P", DownloadDisplay.resolutionOf(item("第1集_720P", null), "any.mp4"));
        assertEquals("4K", DownloadDisplay.resolutionOf(item("正片_4K", null), "any.mp4"));
        // episodeName 空时从文件名解析
        assertEquals("1080P", DownloadDisplay.resolutionOf(item(null, null), "movie.1080p.mkv"));
        // 均无清晰度 -> null
        assertNull(DownloadDisplay.resolutionOf(item("第1集", null), "movie.mkv"));
        assertNull(DownloadDisplay.resolutionOf(item(null, null), "movie.mkv"));
    }

    @Test
    public void doneSignature_joinsPathAndSize() {
        List<VideoInfo> files = new ArrayList<>();
        VideoInfo a = new VideoInfo();
        a.setPath("/a/b.mp4");
        a.setSize(100);
        files.add(a);
        VideoInfo b = new VideoInfo();
        b.setPath("/c/d.mkv");
        b.setSize(200);
        files.add(b);
        assertEquals("/a/b.mp4|100;/c/d.mkv|200;", DownloadDisplay.doneSignature(files));
        assertEquals("", DownloadDisplay.doneSignature(new ArrayList<>()));
    }

    @Test
    public void deleteRecursive_removesNested() throws Exception {
        File root = java.nio.file.Files.createTempDirectory("dl-display-test").toFile();
        File nested = new File(root, "nested");
        assertTrue(nested.mkdirs());
        File f1 = new File(nested, "1.txt");
        File f2 = new File(root, "2.txt");
        assertTrue(f1.createNewFile());
        assertTrue(f2.createNewFile());
        DownloadDisplay.deleteRecursive(root);
        assertFalse(root.exists());
        // null / 不存在 不抛
        DownloadDisplay.deleteRecursive(null);
        DownloadDisplay.deleteRecursive(new File(root, "ghost"));
    }

    private static void assertTrue(boolean b) {
        org.junit.Assert.assertTrue(b);
    }

    private static void assertFalse(boolean b) {
        org.junit.Assert.assertFalse(b);
    }
}
