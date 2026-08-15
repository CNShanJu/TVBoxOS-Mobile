package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import com.blankj.utilcode.util.ToastUtils
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.BaseViewHolder
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.databinding.ActivityLogBinding
import com.github.tvbox.osc.util.AppLog
import com.lxj.xpopup.XPopup
import java.io.File

/**
 * 运行日志页:按天查看(仅显示最近 2000 行)、滚到底、清空、导出
 */
class LogActivity : BaseVbActivity<ActivityLogBinding>() {

    private val dayFiles = ArrayList<File>()
    private var selectedFile: File? = null
    private var dayAdapter: BaseQuickAdapter<String, BaseViewHolder>? = null

    companion object {
        private const val SHOW_MAX_LINES = 1000
    }

    override fun init() {
        mBinding.rvDays.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        mBinding.btnClear.setOnClickListener { confirmClear() }
        mBinding.btnExport.setOnClickListener { export() }
        mBinding.btnScrollBottom.setOnClickListener {
            mBinding.scrollLog.fullScroll(View.FOCUS_DOWN)
        }

        refreshDays()
    }

    override fun onResume() {
        super.onResume()
        refreshDays()
    }

    private fun refreshDays() {
        dayFiles.clear()
        dayFiles.addAll(AppLog.listLogFiles())
        val display = ArrayList<String>()
        for (f in dayFiles) {
            val count = AppLog.readTail(f, 100000).size
            display.add(f.name.replace("app-", "").replace(".log", "") + " (" + count + "条)")
        }
        dayAdapter = object : BaseQuickAdapter<String, BaseViewHolder>(R.layout.item_log_day, display) {
            override fun convert(helper: BaseViewHolder, item: String) {
                helper.setText(R.id.tv_day, item)
                val tv = helper.getView<TextView>(R.id.tv_day)
                tv.setTextColor(getColor(if (dayFiles[helper.layoutPosition] == selectedFile) R.color.colorPrimary else R.color.text_sub_foreground))
                tv.setTypeface(null, if (dayFiles[helper.layoutPosition] == selectedFile) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }
        dayAdapter!!.setOnItemClickListener { _, _, position ->
            if (position in dayFiles.indices) {
                selectedFile = dayFiles[position]
                dayAdapter!!.notifyDataSetChanged()
                refreshContent()
            }
        }
        mBinding.rvDays.adapter = dayAdapter
        if (dayFiles.isNotEmpty()) {
            selectedFile = dayFiles[0]
        } else {
            selectedFile = null
        }
        refreshContent()
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
