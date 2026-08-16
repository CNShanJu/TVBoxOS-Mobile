package com.github.tvbox.osc.util;

import android.text.TextUtils;
import android.util.Log;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;

/**
 * 播放地址解析器:批量下载前,把源站返回的"集标识/内部ID"(如 4|527743|62032)
 * 解析成真实可下载的 HTTP 地址,解析逻辑与播放流程(getPlay → playerContent/解析)保持一致。
 * <p>
 * 支持:直接 HTTP 地址、爬虫(playerContent)返回 parse=0 的直链、
 * json:/parse: 类型的解析接口;需要 WebView 嗅探的地址无法批量解析,返回 null 由调用方跳过。
 */
public class PlayUrlResolver {

    private static final String TAG = "TVBox-Download";

    /**
     * 解析单集真实播放地址;解析失败返回 null。
     *
     * @param sourceKey 来源 key
     * @param playFlag  线路名(如 线路1)
     * @param url       源站返回的地址/内部标识
     */
    public static String resolve(String sourceKey, String playFlag, String url) {
        if (TextUtils.isEmpty(url)) return null;
        // 已是有效 HTTP 地址,直接使用
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        try {
            SourceBean sb = ApiConfig.get().getSource(sourceKey);
            if (sb == null) return null;
            int type = sb.getType();
            if (type == 3) {
                // 爬虫源:playerContent 返回播放信息
                Spider sp = ApiConfig.get().getCSP(sb);
                String json = sp.playerContent(playFlag, url, ApiConfig.get().getVipParseFlags());
                JSONObject result = new JSONObject(json);
                return handleResult(result, playFlag, url);
            }
            if (type == 0 || type == 1) {
                // XML/JSON 接口源:本身是视频格式则直下,否则走源站的解析接口
                if (DefaultConfig.isVideoFormat(url)) return url;
                String playerUrl = sb.getPlayerUrl() == null ? "" : sb.getPlayerUrl().trim();
                return parseJson(playerUrl, url);
            }
            if (type == 4) {
                // HTTP 接口源:play 参数拿播放信息
                String api = sb.getApi();
                if (TextUtils.isEmpty(api)) return null;
                String sep = api.contains("?") ? "&" : "?";
                String req = api + sep + "play=" + encode(url) + "&flag=" + encode(playFlag);
                JSONObject result = new JSONObject(HttpClient.getSync(req, null));
                return handleResult(result, playFlag, url);
            }
        } catch (Throwable th) {
            Log.i(TAG, "resolve 失败: " + url + " -> " + th.getMessage());
        }
        return null;
    }

    /** 处理播放信息结果:parse=0 直链;parse=1 尝试 json 解析 */
    private static String handleResult(JSONObject result, String playFlag, String url) throws JSONException {
        boolean parse = result.optString("parse", "1").equals("1");
        boolean jx = result.optString("jx", "0").equals("1");
        String playUrl = result.optString("playUrl", "");
        String realUrl = result.optString("url", "");
        if (!parse && !jx) {
            String direct = playUrl + realUrl;
            return direct.startsWith("http://") || direct.startsWith("https://") ? direct : null;
        }
        // 需要解析
        if (jx) return null; // 自定义解析列表,批量不支持
        return parseJson(playUrl, realUrl);
    }

    /** json 解析(json:/parse: 接口),失败返回 null */
    private static String parseJson(String playUrl, String videoUrl) {
        try {
            if (TextUtils.isEmpty(playUrl)) return null;
            if (playUrl.startsWith("json:")) {
                playUrl = playUrl.substring(5);
            } else if (playUrl.startsWith("parse:")) {
                // 按名称找解析器
                String name = playUrl.substring(6);
                boolean found = false;
                for (ParseBean pb : ApiConfig.get().getParseBeanList()) {
                    if (pb.getName().equals(name) && pb.getType() == 1) {
                        playUrl = pb.mixUrl();
                        found = true;
                        break;
                    }
                }
                if (!found) return null;
            }
            if (TextUtils.isEmpty(playUrl)) return null;
            String json = HttpClient.getSync(playUrl + encode(videoUrl), null);
            JSONObject rs = parseJsonResult(videoUrl, json);
            String real = rs == null ? null : rs.optString("url", "");
            return TextUtils.isEmpty(real) ? null : real;
        } catch (Throwable th) {
            Log.i(TAG, "json解析失败: " + playUrl + " -> " + th.getMessage());
            return null;
        }
    }

    /** 解析 json 接口返回(兼容 {url:..} / {data:{url:..}}) */
    private static JSONObject parseJsonResult(String input, String json) throws JSONException {
        JSONObject jsonPlayData = new JSONObject(json);
        String url;
        if (jsonPlayData.has("data")) {
            JSONObject data = jsonPlayData.getJSONObject("data");
            url = data.has("url") ? data.getString("url") : "";
        } else {
            url = jsonPlayData.getString("url");
        }
        if (url.startsWith("//")) url = "http:" + url;
        if (!url.startsWith("http")) return null;
        JSONObject taskResult = new JSONObject();
        taskResult.put("url", url);
        return taskResult;
    }

    private static String encode(String url) {
        try {
            return URLEncoder.encode(url, "UTF-8");
        } catch (Exception e) {
            return url;
        }
    }
}
