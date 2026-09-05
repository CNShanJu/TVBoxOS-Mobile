package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.view.View
import android.widget.TextView
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivityLogBinding
import com.github.tvbox.osc.util.LogViewAssembler
import com.lxj.xpopup.XPopup
import java.io.File

/**
 * 运行日志页（双 Tab，数据组装下沉到 [LogViewAssembler]，页面只做交互与展示）
 * <ul>
 *   <li>Tab1 业务日志：LogStore(Room) 结构化日志，模块筛选（全部/下载/播放/订阅/系统/仅失败）；</li>
 *   <li>Tab2 错误日志：本应用 logcat ERROR 级（按天文件），日期选择、复制、清空、导出——
 *       文件读取全部走 LogStore 门面，不再直接依赖 common.AppLog。</li>
 * </ul>
 */
class LogActivity : BaseVbActivity<ActivityLogBinding>() {

    private val dayFiles = ArrayList<File>()
    private var selectedFile: File? = null
    /** 0=业务日志 1=错误日志 */
    private var currentTab = 0
    /** 业务日志筛选：大类型（Category.name()），null=全部 */
    private var filterCategory: String? = null
    /** 业务日志筛选：仅失败（fail/异常打点） */
    private var filterErrorOnly = false

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
        // Tab 选中态:背景用 selector_filter_chip 的 selected 高亮, 文字选中白/未选灰
        mBinding.tvTabBiz.isSelected = tab == 0
        mBinding.tvTabAll.isSelected = tab == 1
        mBinding.tvTabBiz.setTextColor(if (tab == 0) colorOf(R.color.white) else colorOf(R.color.text_sub_foreground))
        mBinding.tvTabAll.setTextColor(if (tab == 1) colorOf(R.color.white) else colorOf(R.color.text_sub_foreground))
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
        // 选中态:背景高亮(selector_filter_chip) + 白字; 未选中灰底灰字
        tv.isSelected = selected
        tv.setTextColor(colorOf(if (selected) R.color.white else R.color.text_sub_foreground))
    }

    private fun colorOf(res: Int): Int = getColor(res)

    // ------------------------------------------------------------------
    // 内容（组装逻辑在 LogViewAssembler）
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
            val text = try {
                LogViewAssembler.bizText(LogStore.get(), category, errorOnly)
            } catch (th: Throwable) {
                th.printStackTrace()
                null
            }
            runOnUiThread {
                mBinding.tvContent.text = text ?: "暂无日志（先到 设置→运行日志 开启采集）"
                mBinding.scrollLog.post { mBinding.scrollLog.fullScroll(View.FOCUS_UP) }
            }
        }.start()
    }

    /** Tab2 错误日志：文件列表/读尾也走 LogStore 门面，后台线程读取,避免大文件卡主线程 */
    private fun loadAllLogs() {
        dayFiles.clear()
        dayFiles.addAll(LogViewAssembler.rawFiles(LogStore.get()))
        if (dayFiles.isEmpty()) {
            selectedFile = null
            mBinding.tvSelectedDay.text = "暂无日志"
            mBinding.tvContent.text = "暂无日志"
            return
        }
        if (selectedFile == null || !dayFiles.contains(selectedFile)) {
            selectedFile = dayFiles[0]
        }
        mBinding.tvSelectedDay.text = selectedFile?.name?.let { LogViewAssembler.dayLabel(it) } ?: "暂无日志"
        val file = selectedFile
        Thread {
            val text = try {
                LogViewAssembler.rawText(LogStore.get(), file)
            } catch (th: Throwable) {
                th.printStackTrace()
                null
            }
            runOnUiThread {
                mBinding.tvContent.text = text ?: "暂无内容"
                mBinding.scrollLog.post { mBinding.scrollLog.fullScroll(View.FOCUS_DOWN) }
            }
        }.start()
    }

    /** 底部抽屉选择日期 */
    private fun showDatePicker() {
        if (dayFiles.isEmpty()) {
            AppBubble.toast("暂无日志")
            return
        }
        val display = Array(dayFiles.size) { i -> LogViewAssembler.dayLabel(dayFiles[i].name) }
        XPopup.Builder(this)
            .asBottomList("选择日期", display) { position, _ ->
                if (position in dayFiles.indices) {
                    selectedFile = dayFiles[position]
                    mBinding.tvSelectedDay.text = display[position]
                    refreshContent()
                }
            }
            .show()
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
        com.github.tvbox.osc.ui.dialog.ConfirmDialog.show(this, "清空日志",
            "确定清空${if (currentTab == 0) "业务日志" else "错误日志"}吗？", "清空", {
                if (currentTab == 0) {
                    LogViewAssembler.clearBiz(LogStore.get())
                    mBinding.tvContent.text = "暂无日志"
                } else {
                    LogViewAssembler.clearRaw(LogStore.get())
                    selectedFile = null
                    refreshContent()
                }
                AppBubble.toast("已清空")
            })
    }

    private fun export() {
        val store = LogStore.get()
        val file = if (currentTab == 0) {
            LogViewAssembler.exportBiz(store, filterCategory, filterErrorOnly)
        } else {
            LogViewAssembler.exportRaw(store)
        }
        if (file == null) {
            AppBubble.toast("暂无日志可导出")
            return
        }
        shareFile(file)
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
