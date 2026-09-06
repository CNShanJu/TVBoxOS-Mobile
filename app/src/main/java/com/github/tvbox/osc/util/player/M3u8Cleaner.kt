package com.github.tvbox.osc.util.player

import org.apache.commons.lang3.StringUtils

/**
 * m3u8 播放地址净化(自 PlayFragment 抽取的纯逻辑,无 UI/播放器依赖;Java→Kotlin 化,改进.txt §八):
 * 识别并剔除少数派(minority)分片(广告/占位),必要时补齐相对路径与 EXT-X-KEY。
 */
object M3u8Cleaner {

    /**
     * 移除 m3u8 中的少数派分片(广告):同前缀出现次数最多的为正常分片,其余剔除。
     *
     * @param tsUrlPre   该 m3u8 所在目录(用于拼接相对路径)
     * @param m3u8content m3u8 文本
     * @return 净化后的 m3u8;无法识别(前缀过多/结构异常)返回 null,由调用方回退直接播放
     */
    @JvmStatic
    fun removeMinorityUrl(tsUrlPre: String, m3u8content: String): String? {
        if (!m3u8content.startsWith("#EXTM3U")) return null
        var linesplit = "\n"
        if (m3u8content.contains("\r\n")) linesplit = "\r\n"
        val lines = m3u8content.split(linesplit).toTypedArray()

        val preUrlMap = HashMap<String, Int>()
        for (line in lines) {
            if (line.isEmpty() || line[0] == '#') continue
            val ilast = line.lastIndexOf('.')
            if (ilast <= 4) continue
            val preUrl = line.substring(0, ilast - 4)
            preUrlMap[preUrl] = (preUrlMap[preUrl] ?: 0) + 1
        }
        if (preUrlMap.size <= 1) return null
        if (preUrlMap.size > 5) return null // too many different url, can not identify ads url
        var maxTimes = 0
        var maxTimesPreUrl = ""
        for ((preUrl, times) in preUrlMap) {
            if (times > maxTimes) {
                maxTimesPreUrl = preUrl
                maxTimes = times
            }
        }
        if (maxTimes == 0) return null

        var dealedExtXKey = false
        for (i in lines.indices) {
            if (!dealedExtXKey && lines[i].startsWith("#EXT-X-KEY")) {
                val keyUrl = StringUtils.substringBetween(lines[i], "URI=\"", "\"")
                if (keyUrl != null && !keyUrl.startsWith("http://") && !keyUrl.startsWith("https://")) {
                    val newKeyUrl = if (keyUrl[0] == '/') {
                        val ifirst = tsUrlPre.indexOf('/', 9) // skip https://, http://
                        tsUrlPre.substring(0, ifirst) + keyUrl
                    } else {
                        tsUrlPre + keyUrl
                    }
                    lines[i] = lines[i].replace("URI=\"" + keyUrl + "\"", "URI=\"" + newKeyUrl + "\"")
                }
                dealedExtXKey = true
            }
            if (lines[i].isEmpty() || lines[i][0] == '#') continue
            if (lines[i].startsWith(maxTimesPreUrl)) {
                if (!lines[i].startsWith("http://") && !lines[i].startsWith("https://")) {
                    if (lines[i][0] == '/') {
                        val ifirst = tsUrlPre.indexOf('/', 9) // skip https://, http://
                        lines[i] = tsUrlPre.substring(0, ifirst) + lines[i]
                    } else {
                        lines[i] = tsUrlPre + lines[i]
                    }
                }
            } else {
                if (i > 0 && lines[i - 1].isNotEmpty() && lines[i - 1][0] == '#') {
                    lines[i - 1] = ""
                }
                lines[i] = ""
            }
        }
        return StringUtils.join(lines, linesplit)
    }
}
