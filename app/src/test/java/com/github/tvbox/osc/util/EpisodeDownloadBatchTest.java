package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** EpisodeDownloadBatch 纯工具方法的 JVM 单测 */
public class EpisodeDownloadBatchTest {

    @Test
    public void resolutionLabel_mapsHeight() {
        assertEquals("4K", EpisodeDownloadBatch.resolutionLabel(new int[]{3840, 2160}));
        assertEquals("2K", EpisodeDownloadBatch.resolutionLabel(new int[]{2560, 1440}));
        assertEquals("1080P", EpisodeDownloadBatch.resolutionLabel(new int[]{1920, 1080}));
        assertEquals("720P", EpisodeDownloadBatch.resolutionLabel(new int[]{1280, 720}));
        assertEquals("480P", EpisodeDownloadBatch.resolutionLabel(new int[]{854, 640}));
        // 低于 500 高度无法归类(实现阈值),返回 null
        assertEquals(null, EpisodeDownloadBatch.resolutionLabel(new int[]{480, 360}));
    }

    @Test
    public void resolutionLabel_unknownReturnsNull() {
        assertEquals(null, EpisodeDownloadBatch.resolutionLabel(null));
        assertEquals(null, EpisodeDownloadBatch.resolutionLabel(new int[]{1920}));
        assertEquals(null, EpisodeDownloadBatch.resolutionLabel(new int[]{0, 0}));
    }

    @Test
    public void containsResolution_detectsLabelInName() {
        assertTrue(EpisodeDownloadBatch.containsResolution("第01集_1080P"));
        assertTrue(EpisodeDownloadBatch.containsResolution("正片4K版"));
        assertFalse(EpisodeDownloadBatch.containsResolution("第01集"));
        assertFalse(EpisodeDownloadBatch.containsResolution(null));
    }

    @Test
    public void resolveEpisodeId_keepsProvided() {
        assertEquals("ep-123",
                EpisodeDownloadBatch.resolveEpisodeId("src", "vod", "f", null, "第一集", "ep-123"));
    }

    @Test
    public void countEnqueueOutcome_classifies() {
        EpisodeDownloadBatch.Outcome out = new EpisodeDownloadBatch.Outcome();
        EpisodeDownloadBatch.countEnqueueOutcome(true, 1, out);      // added
        EpisodeDownloadBatch.countEnqueueOutcome(false, 1, out);     // downloadedExisted
        EpisodeDownloadBatch.countEnqueueOutcome(false, 0, out);     // existedInQueue
        assertEquals(1, out.added);
        assertEquals(1, out.downloadedExisted);
        assertEquals(1, out.existedInQueue);
        assertEquals(0, out.failed);
    }
}
