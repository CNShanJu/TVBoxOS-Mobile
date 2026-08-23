package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivityDownloadBinding
import com.github.tvbox.osc.ui.fragment.DownloadFragment
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.util.DownloadConfig
import com.lxj.xpopup.XPopup

/**
 * 下载页(我的-下载):内部按 正在下载 / 下载完成 两个 tab 展示。
 * 标题栏:返回 + "下载管理" + 右侧齿轮(下载设置:并发/仅WiFi,与全局设置页同一事实源)。
 */
class DownloadActivity : BaseVbActivity<ActivityDownloadBinding>() {
    override fun init() {
        supportFragmentManager.beginTransaction()
            .replace(mBinding.container.id, DownloadFragment())
            .commitAllowingStateLoss()
        // 标题栏齿轮 = 下载设置入口(与设置页"下载设置"分组共用 DownloadConfig)
        mBinding.titleBar.rightView.setOnClickListener { showDownloadSettings() }
    }

    /** 下载设置弹层:并发数 + 仅WiFi(样式与全局设置一致,通过 DownloadConfig 读写) */
    fun showDownloadSettings() {
        val wifiOnly = DownloadConfig.isWifiOnly()
        val options = arrayOf(
            "下载并发（当前 " + DownloadConfig.getMaxConcurrent() + "）",
            "仅 WiFi 下载（" + (if (wifiOnly) "开" else "关") + "）"
        )
        XPopup.Builder(this)
            .asBottomList("下载设置", options) { position: Int, _: String? ->
                if (position == 0) {
                    val concurrent = arrayOf("并发 1", "并发 2", "并发 3", "并发 4", "并发 5")
                    XPopup.Builder(this)
                        .asBottomList("选择下载并发", concurrent) { p: Int, _: String? ->
                            DownloadConfig.setMaxConcurrent(p + 1)
                        }
                        .show()
                } else {
                    val newVal = !DownloadConfig.isWifiOnly()
                    DownloadConfig.setWifiOnly(newVal)
                    AppBubble.toast("仅 WiFi 下载已" + if (newVal) "开启" else "关闭")
                }
            }
            .show()
    }

    override fun onBackPressed() {
        val f = supportFragmentManager.findFragmentById(mBinding.container.id) as? DownloadFragment
        if (f != null && f.onBackPressed()) return
        super.onBackPressed()
    }
}
