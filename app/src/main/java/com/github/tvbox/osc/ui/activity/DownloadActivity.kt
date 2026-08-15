package com.github.tvbox.osc.ui.activity

import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivityDownloadBinding
import com.github.tvbox.osc.ui.fragment.DownloadFragment

/**
 * 下载页(我的-下载):内部按 正在下载 / 下载完成 两个 tab 展示
 */
class DownloadActivity : BaseVbActivity<ActivityDownloadBinding>() {
    override fun init() {
        supportFragmentManager.beginTransaction()
            .replace(mBinding.container.id, DownloadFragment())
            .commitAllowingStateLoss()
    }

    override fun onBackPressed() {
        val f = supportFragmentManager.findFragmentById(mBinding.container.id) as? DownloadFragment
        if (f != null && f.onBackPressed()) return
        super.onBackPressed()
    }
}
