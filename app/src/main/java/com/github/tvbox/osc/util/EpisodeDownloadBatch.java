package com.github.tvbox.osc.util;

import android.text.TextUtils;
import android.util.Log;

import com.github.catvod.crawler.PlayUrlResolver;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.download.DownloadFacade;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 整剧批量入队下载协调逻辑(自 DetailActivity 抽出, 缩小宿主类体积):
 * 逐集解析真实地址(当前集优先复用播放器已解析结果, 其余按源解析) → 拼集名/分辨率 → 统一剧集标识
 * → 经 {@link DownloadFacade} 入队并计数。宿主只负责线程调度、确认弹窗与结果提示。
 */
public final class EpisodeDownloadBatch {

    /** 当前播放剧集上下文(仅“当前集”复用播放器解析; 不在播放该集时返回 null 均可) */
    public interface CurrentEpisode {
        /** 播放器最终解析地址;无则返回 null */
        String finalUrl();

        /** 播放器当前请求头(防盗链);无则返回 null */
        Map<String, String> playHeaders();
    }

    /** 批量入队结果计数(与旧实现口径一致) */
    public static final class Outcome {
        public int added = 0;
        public int downloadedExisted = 0;
        public int existedInQueue = 0;
        public int failed = 0;
    }

    private EpisodeDownloadBatch() {
    }

    /**
     * 批量入队完成后的提示文案(纯映射;UI 仅弹 toast)。优先"有新增"、
     * 其次"均已存在(已下载/已在任务中)"、再"全部失败";空输入返回 null 由调用方处理。
     */
    public static String toastMessage(Outcome r) {
        if (r.added > 0) {
            int dups = r.downloadedExisted + r.existedInQueue;
            return dups > 0
                    ? "已加入 " + r.added + " 个下载任务," + dups + " 个已存在"
                    : "已加入 " + r.added + " 个下载任务,可在\"我的-下载\"查看";
        }
        if (r.downloadedExisted > 0 || r.existedInQueue > 0) {
            if (r.downloadedExisted > 0 && r.existedInQueue > 0) {
                return r.downloadedExisted + " 集已下载," + r.existedInQueue + " 集已在任务中";
            }
            if (r.downloadedExisted > 0) {
                return "所选剧集均已下载完成";
            }
            return "所选剧集已在下载任务中";
        }
        if (r.failed > 0) {
            return r.failed > 1
                    ? "所选剧集解析失败(" + r.failed + " 集),该源可能仅支持下载当前播放的剧集"
                    : "该集解析失败,该源可能仅支持下载当前播放的剧集,请先播放该集再试";
        }
        return null;
    }

    /** 计算统一剧集标识:已有 episodeId 直接用;否则按集名在全集列表中的索引回退生成 */
    public static String resolveEpisodeId(String sourceKey, String vodId, String playFlag,
                                         List<VodInfo.VodSeries> seriesList, String sName, String episodeId) {
        if (episodeId != null && !episodeId.isEmpty()) return episodeId;
        int idx = 0;
        if (seriesList != null) {
            for (int i = 0; i < seriesList.size(); i++) {
                if (seriesList.get(i) != null && seriesList.get(i).name != null
                        && seriesList.get(i).name.equals(sName)) {
                    idx = i;
                    break;
                }
            }
        }
        return DownloadFacade.get().buildEpisodeId(sourceKey, vodId, playFlag, idx);
    }

    /**
     * 入队结果归类:ok=true → added;
     * ok=false 按“已下载完成(状态1)”/“已在任务中”分别计入 downloadedExisted/existedInQueue。
     */
    public static void countEnqueueOutcome(boolean ok, int episodeState, Outcome out) {
        if (out == null) return;
        if (ok) {
            out.added++;
        } else {
            if (episodeState == 1) out.downloadedExisted++;
            else out.existedInQueue++;
        }
    }

    /**
     * 批量解析并入队。
     *
     * @param selected    已勾选的剧集列表
     * @param vodInfo     详情数据(sourceKey/id/pic/playFlag/seriesMap 均由此读取)
     * @param sourceName  来源名(仅用于入队/日志)
     * @param vodName     剧名(用于文件名)
     * @param currentName 当前正在播放的集名(可 null, 用于识别“当前集”走播放器解析)
     * @param resLabel    分辨率标签(720P/2K/4K 等; 由宿主按播放器画面尺寸生成, 可 null)
     * @param current     当前播放上下文;可 null
     */
    public static Outcome enqueue(List<VodInfo.VodSeries> selected,
                                  VodInfo vodInfo,
                                  String sourceName,
                                  String vodName,
                                  String currentName,
                                  String resLabel,
                                  CurrentEpisode current) {
        Outcome out = new Outcome();
        if (selected == null || selected.isEmpty() || vodInfo == null) return out;
        List<VodInfo.VodSeries> seriesList =
                vodInfo.seriesMap == null ? null : vodInfo.seriesMap.get(vodInfo.playFlag);
        if (seriesList == null || seriesList.isEmpty()) return out;
        final String sourceKey = vodInfo.sourceKey;
        final String playFlag = vodInfo.playFlag;
        final String vodId = vodInfo.id;

        for (VodInfo.VodSeries s : selected) {
            if (s == null) continue;
            try {
                // 解析真实地址 + 源要求的请求头(防盗链源下载必须携带,否则"能播不能下")
                PlayUrlResolver.ResolveResult rr = null;
                if (s.name != null && s.name.equals(currentName) && current != null) {
                    String finalUrl = current.finalUrl();
                    if (!TextUtils.isEmpty(finalUrl)) {
                        // 当前集: 解析失败回退播放地址, 解析结果无头时补播放器 UA/Referer
                        rr = PlayUrlResolver.resolveCurrentWithPlaybackHeaders(
                                sourceKey, playFlag, s.url, current.playHeaders(), finalUrl);
                    }
                }
                if (rr == null) {
                    rr = PlayUrlResolver.resolveWithHeader(sourceKey, playFlag, s.url);
                }
                String url = rr == null ? null : rr.url;
                if (TextUtils.isEmpty(url) || !(url.startsWith("http://") || url.startsWith("https://"))) {
                    Log.i("TVBox-Download", "  - " + s.name + " 解析失败/无有效地址,跳过");
                    out.failed++;
                    continue;
                }
                // 文件名拼接分辨率:剧名_集名_720P.mp4;集名已含分辨率字样则不多拼;单集(名=剧名)不拼
                String epName = s.name;
                if (resLabel != null && s.name != null && !s.name.isEmpty()
                        && !s.name.equals(vodName) && !containsResolution(s.name)) {
                    epName = s.name + "_" + resLabel;
                }
                // 统一剧集标识(与详情页选集一一对应,精确去重):优先选集携带的 episodeId,旧数据按集名回退索引
                String episodeId = resolveEpisodeId(sourceKey, vodId, playFlag, seriesList, s.name, s.episodeId);
                boolean ok = DownloadFacade.get().enqueue(new com.github.tvbox.osc.download.DownloadRequest(
                        url, sourceKey, playFlag, s.url, episodeId,
                        vodInfo.pic, rr.headers, sourceName, vodName, epName));
                Log.i("TVBox-Download", "  - " + s.name + " enqueue=" + ok + " 文件名=" + epName + " url=" + url);
                // 归类:added / 已下载完成(状态1) / 已在任务中
                countEnqueueOutcome(ok, DownloadFacade.get().getEpisodeState(episodeId, sourceName, vodName, s.name), out);
            } catch (Throwable th) {
                Log.e("TVBox-Download", "批量入队异常: " + (s.name == null ? "" : s.name), th);
                out.failed++;
            }
        }
        return out;
    }

    /** 集名是否已含分辨率字样(4K/2K/1080P/720P 等),含则不再拼接 */
    public static boolean containsResolution(String name) {
        if (name == null) return false;
        String n = name.toUpperCase(Locale.ROOT);
        return n.contains("4K") || n.contains("2K") || n.contains("2160P") || n.contains("1440P")
                || n.contains("1080P") || n.contains("720P") || n.contains("480P") || n.contains("360P");
    }

    /** 由视频宽高生成分辨率标签(高≥2000→4K;≥1400→2K;≥1000→1080P;≥700→720P;≥500→480P);未知返回 null */
    public static String resolutionLabel(int[] size) {
        if (size == null || size.length < 2) return null;
        int h = size[1];
        if (h <= 0) return null;
        if (h >= 2000) return "4K";
        if (h >= 1400) return "2K";
        if (h >= 1000) return "1080P";
        if (h >= 700) return "720P";
        if (h >= 500) return "480P";
        return null;
    }
}
