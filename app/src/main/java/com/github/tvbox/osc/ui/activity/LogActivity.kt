package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogFilter
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivityLogBinding
import com.github.tvbox.osc.util.AppLog
import com.lxj.xpopup.XPopup
import java.io.File

/**
 * 运行日志页（升级：双 Tab）
 * <ul>
 *   <li>Tab1 业务日志：LogStore(Room) 结构化日志，模块筛选（全部/下载/播放/订阅/系统/仅错误），
 *       展示格式 [时间] [大类型] [小类型] 干了啥 ✓/✗；</li>
 *   <li>Tab2 全部日志：logcat 原始流（package:mine 按天文件），日期选择、复制、清空、导出。</li>
 * </ul>
 */
class LogActivity : BaseVbActivity<ActivityLogBinding>() {

    private val dayFiles = ArrayList<File>()
    private var selectedFile: File? = null
    /** 0=业务日志 1=全部日志 */
    private var currentTab = 0
    /** 业务日志筛选：大类型（Category.name()），null=全部 */
    private var filterCategory: String? = null
    /** 业务日志筛选：仅错误 */
    private var filterErrorOnly = false

    companion object {
        private const val SHOW_MAX_LINES = 1000
        private const val BIZ_MAX_LINES = 300
    }

    override fun init() {
        mBinding.btnClear.setOnClickListener { confirmClear() }
        mBinding.btnExport.setOnClickListener { export() }
        mBinding.btnScrollBottom.setOnClickListener { scrollBottom() }
        mBinding.btnCopy.setOnClickListener { copyContent() }
        mBinding.llDatePicker.setOnClickListener { showDatePicker() }

        // Tab 切换
        mBinding.tvTabBiz.setOnClickListener { switchTab(0) }
        mBinding.tvTabAll.setOnClickListener { switchTab(1) }

        // 业务日志模块筛选
        mBinding.ftAll.setOnClickListener { setBizFilter(null, false) }
        mBinding.ftDownload.setOnClickListener { setBizFilter(Category.DOWNLOAD.name, false) }
        mBinding.ftPlayer.setOnClickListener { setBizFilter(Category.PLAYER.name, false) }
        mBinding.ftSubscription.setOnClickListener { setBizFilter(Category.SUBSCRIPTION.name, false) }
        mBinding.ftSystem.setOnClickListener { setBizFilter(Category.SYSTEM.name, false) }
        mBinding.ftError.setOnClickListener { setBizFilter(filterCategory, true) }

        switchTab(0)
    }

    override fun onResume() {
        super.onResume()
        refreshContent()
    }

    // ------------------------------------------------------------------
    // Tab 切换
    // ------------------------------------------------------------------

    private fun switchTab(tab: Int) {
        currentTab = tab
        mBinding.tvTabBiz.setTypeface(null, if (tab == 0) Typeface.BOLD else Typeface.NORMAL)
        mBinding.tvTabAll.setTypeface(null, if (tab == 1) Typeface.BOLD else Typeface.NORMAL)
        mBinding.tvTabBiz.setTextColor(if (tab == 0) colorOf(R.color.text_foreground) else colorOf(R.color.text_sub_foreground))
        mBinding.tvTabAll.setTextColor(if (tab == 1) colorOf(R.color.text_foreground) else colorOf(R.color.text_sub_foreground))
        val isBiz = tab == 0
        mBinding.tvTip.visibility = if (isBiz) View.GONE else View.VISIBLE
        mBinding.llFilter.visibility = if (isBiz) View.VISIBLE else View.GONE
        mBinding.llDatePicker.visibility = if (isBiz) View.GONE else View.VISIBLE
        refreshContent()
    }

    private fun setBizFilter(category: String?, errorToggle: Boolean) {
        if (errorToggle) {
            filterErrorOnly = !filterErrorOnly
        } else {
            filterCategory = category
            filterErrorOnly = false
        }
        updateFilterButtons()
        refreshContent()
    }

    private fun updateFilterButtons() {
        setFilterSelected(mBinding.ftAll, filterCategory == null && !filterErrorOnly)
        setFilterSelected(mBinding.ftDownload, filterCategory == Category.DOWNLOAD.name)
        setFilterSelected(mBinding.ftPlayer, filterCategory == Category.PLAYER.name)
        setFilterSelected(mBinding.ftSubscription, filterCategory == Category.SUBSCRIPTION.name)
        setFilterSelected(mBinding.ftSystem, filterCategory == Category.SYSTEM.name)
        setFilterSelected(mBinding.ftError, filterErrorOnly)
    }

    private fun setFilterSelected(tv: TextView, selected: Boolean) {
        tv.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        tv.setTextColor(colorOf(if (selected) R.color.text_foreground else R.color.text_sub_foreground))
    }

    private fun colorOf(res: Int): Int = getColor(res)

    // ------------------------------------------------------------------
    // 内容
    // ------------------------------------------------------------------

    private fun refreshContent() {
        if (currentTab == 0) loadBizLogs() else loadAllLogs()
    }

    /** Tab1 业务日志：LogStore 查询在后台线程（Room 禁止主线程查询） */
    private fun loadBizLogs() {
        val category = filterCategory
        val errorOnly = filterErrorOnly
        mBinding.tvContent.text = "加载中..."
        Thread {
            val entries = try {
                val filter = LogFilter().apply {
                    this.category = category
                    minLevel = if (errorOnly) LogStore.LEVEL_ERROR else LogStore.LEVEL_INFO
                    limit = BIZ_MAX_LINES
                }
                LogStore.get()?.query(filter)
            } catch (th: Throwable) {
                th.printStackTrace()
                null
            }
            runOnUiThread {
                val store = LogStore.get()
                if (entries == null || entries.isEmpty()) {
                    mBinding.tvContent.text = "暂无日志（先到 设置→运行日志 开启采集）"
                    return@runOnUiThread
                }
                val sb = StringBuilder(entries.size * 96)
                for (e in entries) sb.append(store?.formatEntry(e)).append("\n")
                mBinding.tvContent.text = sb.toString()
                mBinding.scrollLog.post { mBinding.scrollLog.fullScroll(View.FOCUS_UP) }
            }
        }.start()
    }

    /** Tab2 全部日志：AppLog 按天文件，显示尾部 SHOW_MAX_LINES 行 */
    private fun loadAllLogs() {
        dayFiles.clear()
        dayFiles.addAll(AppLog.listLogFiles())
        if (dayFiles.isEmpty()) {
            selectedFile = null
            mBinding.tvSelectedDay.text = "暂无日志"
            renderAllLogs()
            return
        }
        if (selectedFile == null || !dayFiles.contains(selectedFile)) {
            selectedFile = dayFiles[0]
        }
        updateSelectedDayText()
        renderAllLogs()
    }

    private fun updateSelectedDayText() {
        val f = selectedFile ?: return
        val count = AppLog.readTail(f, 100000).size
        val date = f.name.replace("app-", "").replace("logcat-", "").replace(".log", "")
        mBinding.tvSelectedDay.text = "$date ($count 条)"
    }

    /** 底部抽屉选择日期 */
    private fun showDatePicker() {
        if (dayFiles.isEmpty()) {
            AppBubble.toast("暂无日志")
            return
        }
        val display = Array(dayFiles.size) { i ->
            val f = dayFiles[i]
            val count = AppLog.readTail(f, 100000).size
            f.name.replace("app-", "").replace("logcat-", "").replace(".log", "") + " ($count 条)"
        }
        XPopup.Builder(this)
            .asBottomList("选择日期", display) { position, _ ->
                if (position in dayFiles.indices) {
                    selectedFile = dayFiles[position]
                    updateSelectedDayText()
                    renderAllLogs()
                }
            }
            .show()
    }

    /** 只显示文件末尾最新的 SHOW_MAX_LINES 行,避免大日志卡顿 */
    private fun renderAllLogs() {
        val file = selectedFile
        if (file == null) {
            mBinding.tvContent.text = "暂无日志"
            return
        }
        val lines = AppLog.readTail(file, SHOW_MAX_LINES)
        val sb = StringBuilder(lines.size * 64)
        for (line in lines) sb.append(line).append("\n")
        if (sb.isEmpty()) {
            mBinding.tvContent.text = "暂无内容"
        } else {
            sb.append("\n—— 仅显示最近 ").append(lines.size).append(" 行 ——")
            mBinding.tvContent.text = sb.toString()
        }
        mBinding.scrollLog.post { mBinding.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun scrollBottom() {
        mBinding.scrollLog.fullScroll(View.FOCUS_DOWN)
    }

    private fun copyContent() {
        val text = mBinding.tvContent.text?.toString() ?: ""
        if (text.isEmpty()) {
            AppBubble.toast("暂无内容可复制")
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("运行日志", text))
        AppBubble.toast("已复制当前日志内容")
    }

    private fun confirmClear() {
        com.github.tvbox.osc.ui.dialog.ConfirmDialog(this, "清空日志",
            "确定清空${if (currentTab == 0) "业务日志" else "全部日志"}吗？", "清空", {
                if (currentTab == 0) {
                    LogStore.get()?.clearAll()
                    mBinding.tvContent.text = "暂无日志"
                } else {
                    AppLog.clearAll()
                    selectedFile = null
                    refreshContent()
                }
                AppBubble.toast("已清空")
            }).show()
    }

    private fun export() {
        if (currentTab == 0) {
            val store = LogStore.get() ?: run {
                AppBubble.toast("暂无日志可导出")
                return
            }
            val filter = LogFilter().apply {
                category = filterCategory
                minLevel = if (filterErrorOnly) LogStore.LEVEL_ERROR else LogStore.LEVEL_INFO
                limit = 2000
            }
            val file = store.export(filter)
            if (file == null) {
                AppBubble.toast("暂无日志可导出")
                return
            }
            shareFile(file)
        } else {
            val file = AppLog.exportAll()
            if (file == null) {
                AppBubble.toast("暂无日志可导出")
                return
            }
            shareFile(file)
        }
    }

    private fun shareFile(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                packageName + ".fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "导出运行日志"))
        } catch (th: Throwable) {
            th.printStackTrace()
            AppBubble.toast("导出失败:" + th.message)
        }
    }
}
