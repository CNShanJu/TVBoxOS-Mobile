package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.view.View
import android.widget.TextView
import com.blankj.utilcode.util.ToastUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivityLogBinding
import com.github.tvbox.osc.util.AppLog
import com.lxj.xpopup.XPopup
import java.io.File

/**
 * 运行日志页:底部抽屉选择日期(仅显示最近 1000 行)、滚到底、复制、清空、导出
 */
class LogActivity : BaseVbActivity<ActivityLogBinding>() {

    private val dayFiles = ArrayList<File>()
    private var selectedFile: File? = null

    companion object {
        private const val SHOW_MAX_LINES = 1000
    }

    override fun init() {
        mBinding.btnClear.setOnClickListener { confirmClear() }
        mBinding.btnExport.setOnClickListener { export() }
        mBinding.btnScrollBottom.setOnClickListener {
            mBinding.scrollLog.fullScroll(View.FOCUS_DOWN)
        }
        // 标题栏右侧复制按钮:一键复制当前日志内容(子 View,与返回图标同一水平线)
        mBinding.btnCopy.setOnClickListener {
            val text = mBinding.tvContent.text?.toString() ?: ""
            if (text.isEmpty()) {
                ToastUtils.showShort("暂无内容可复制")
                return@setOnClickListener
            }
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("运行日志", text))
            ToastUtils.showShort("已复制当前日志内容")
        }
        // 日期选择:点击弹出底部抽屉
        mBinding.llDatePicker.setOnClickListener { showDatePicker() }

        refreshDays()
    }

    override fun onResume() {
        super.onResume()
        refreshDays()
    }

    private fun refreshDays() {
        dayFiles.clear()
        dayFiles.addAll(AppLog.listLogFiles())
        if (dayFiles.isEmpty()) {
            selectedFile = null
            mBinding.tvSelectedDay.text = "暂无日志"
            refreshContent()
            return
        }
        if (selectedFile == null || !dayFiles.contains(selectedFile)) {
            selectedFile = dayFiles[0]
        }
        updateSelectedDayText()
        refreshContent()
    }

    private fun updateSelectedDayText() {
        val f = selectedFile ?: return
        val count = AppLog.readTail(f, 100000).size
        val date = f.name.replace("app-", "").replace(".log", "")
        mBinding.tvSelectedDay.text = "$date ($count 条)"
    }

    /** 底部抽屉选择日期 */
    private fun showDatePicker() {
        if (dayFiles.isEmpty()) {
            ToastUtils.showShort("暂无日志")
            return
        }
        val display = Array(dayFiles.size) { i ->
            val f = dayFiles[i]
            val count = AppLog.readTail(f, 100000).size
            f.name.replace("app-", "").replace(".log", "") + " ($count 条)"
        }
        XPopup.Builder(this)
            .asBottomList("选择日期", display) { position, _ ->
                if (position in dayFiles.indices) {
                    selectedFile = dayFiles[position]
                    updateSelectedDayText()
                    refreshContent()
                }
            }
            .show()
    }

    /** 只显示文件末尾最新的 SHOW_MAX_LINES 行,避免大日志卡顿 */
    private fun refreshContent() {
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
        // 自动滚到底部(查看最新日志)
        mBinding.scrollLog.post { mBinding.scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun confirmClear() {
        XPopup.Builder(this)
            .asConfirm("清空日志", "确定清空全部运行日志吗？") {
                AppLog.clearAll()
                selectedFile = null
                refreshDays()
                ToastUtils.showShort("已清空")
            }.show()
    }

    private fun export() {
        val file = AppLog.exportAll()
        if (file == null) {
            ToastUtils.showShort("暂无日志可导出")
            return
        }
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
            ToastUtils.showShort("导出失败:" + th.message)
        }
    }
}
