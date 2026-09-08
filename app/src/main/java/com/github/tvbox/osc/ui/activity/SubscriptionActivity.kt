package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import com.blankj.utilcode.util.ClipboardUtils
import com.blankj.utilcode.util.LogUtils
import com.github.tvbox.osc.util.AppBubble
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.bean.Source
import com.github.tvbox.osc.bean.Subscription
import com.github.tvbox.osc.databinding.ActivitySubscriptionBinding
import com.github.tvbox.osc.ui.adapter.SubscriptionAdapter
import com.github.tvbox.osc.ui.dialog.ChooseSourceDialog
import com.github.tvbox.osc.ui.dialog.SubsTipDialog
import com.github.tvbox.osc.ui.dialog.SubsciptionDialog
import com.github.tvbox.osc.ui.dialog.SubsciptionDialog.OnSubsciptionListener
import com.github.tvbox.osc.log.Category
import com.github.tvbox.osc.log.LogStore
import com.github.tvbox.osc.util.AppLog
import com.github.tvbox.osc.util.HCallBack
import com.github.tvbox.osc.util.HttpClient
import com.github.tvbox.osc.util.SubscriptionConfig
import com.github.tvbox.osc.util.Utils
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lxj.xpopup.XPopup

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.function.Consumer

class SubscriptionActivity : BaseVbActivity<ActivitySubscriptionBinding>() {

    private var mBeforeUrl = SubscriptionConfig.getApiUrl()
    private var mSelectedUrl = ""
    private var mSubscriptions: MutableList<Subscription> = SubscriptionConfig.getSubscriptions().toMutableList()
    private var mSubscriptionAdapter = SubscriptionAdapter()
    private val mSources: MutableList<Source> = ArrayList()

    override fun init() {

        mBinding.rv.setAdapter(mSubscriptionAdapter)
        mSubscriptions.forEach(Consumer { item: Subscription ->
            if (item.isChecked) {
                mSelectedUrl = item.url
            }
        })

        mSubscriptionAdapter.setNewData(mSubscriptions)
        updateEmptyState()
        mBinding.ivUseTip.setOnClickListener {
            XPopup.Builder(this)
                .asCustom(SubsTipDialog(this))
                .show()
        }

        mBinding.titleBar.rightView.setOnClickListener {//添加订阅
            XPopup.Builder(this)
                .autoFocusEditText(false)
                .asCustom(
                    SubsciptionDialog(
                        this,
                        "订阅: " + (mSubscriptions.size + 1),
                        object : OnSubsciptionListener {
                            override fun onConfirm(
                                name: String,
                                url: String,
                                checked: Boolean
                            ) { //只有addSub2List用到,看注释,单线路才生效,其余方法仅作为参数继续传递
                                for (item in mSubscriptions) {
                                    if (item.url == url) {
                                        AppBubble.toastLong("订阅地址与" + item.name + "相同")
                                        return
                                    }
                                }
                                addSubscription(name, url, checked)
                            }

                            override fun chooseLocal(checked: Boolean) { //本地导入
                                pickFile(checked)
                            }
                        })
                ).show()
        }

        mSubscriptionAdapter.setOnItemChildClickListener { _: BaseQuickAdapter<*, *>?, view: View, position: Int ->
            LogUtils.d("删除订阅")
            if (view.id == R.id.iv_del) {
                if (position >= mSubscriptions.size) return@setOnItemChildClickListener
                val target = mSubscriptions[position]
                // 允许删除"当前勾选/正在使用"的订阅(如导入坏订阅也能清理):
                // 删除后自动切换当前订阅(置顶优先,否则取首项;删光则清空)
                val delMsg = if (target.isChecked) {
                    if (mSubscriptions.size <= 1) {
                        "该订阅为当前正在使用的订阅,删除后列表将清空,确定删除吗？"
                    } else {
                        "该订阅为当前正在使用的订阅,删除后将自动切换到其它订阅,确定删除吗？"
                    }
                } else {
                    "确定删除订阅吗？"
                }
                com.github.tvbox.osc.ui.dialog.ConfirmDialog.show(
                    this@SubscriptionActivity,
                    "删除订阅",
                    delMsg,
                    "删除"
                ) {
                    if (position >= mSubscriptions.size) return@show
                    val deleted = mSubscriptions.removeAt(position)
                    AppLog.log("订阅管理", "删除订阅: " + deleted.name + "  " + deleted.url)
                    LogStore.log(Category.SUBSCRIPTION, "订阅: 删除 " + deleted.name)
                    if (deleted.isChecked) {
                        // 自动重选当前订阅:置顶优先,否则取首项;无订阅则清空当前
                        val next = mSubscriptions.firstOrNull { it.isTop }
                            ?: mSubscriptions.firstOrNull()
                        for (s in mSubscriptions) s.setChecked(false)
                        if (next != null) {
                            next.setChecked(true)
                            mSelectedUrl = next.url
                            LogStore.log(Category.SUBSCRIPTION, "订阅: 删除后自动切换到 " + next.name)
                        } else {
                            mSelectedUrl = ""
                        }
                    }
                    //删除/选择只刷新,不触发重新排序
                    mSubscriptionAdapter.notifyDataSetChanged()
                    updateEmptyState()
                }
            }
        }

        mSubscriptionAdapter.setOnItemClickListener { _: BaseQuickAdapter<*, *>?, _: View?, position: Int ->  //选择订阅
            for (i in mSubscriptions.indices) {
                val subscription = mSubscriptions[i]
                if (i == position) {
                    subscription.setChecked(true)
                    mSelectedUrl = subscription.url
                } else {
                    subscription.setChecked(false)
                }
            }
            val chosen = mSubscriptions[position]
            AppLog.log("订阅管理", "选择订阅: " + chosen.name + "  " + chosen.url)
            LogStore.log(Category.SUBSCRIPTION, "订阅: 切换到 " + chosen.name)
            //删除/选择只刷新,不触发重新排序
            mSubscriptionAdapter.notifyDataSetChanged()
        }

        mSubscriptionAdapter.onItemLongClickListener =
            BaseQuickAdapter.OnItemLongClickListener { adapter: BaseQuickAdapter<*, *>?, view: View, position: Int ->
                val item = mSubscriptions[position]
                XPopup.Builder(this)
                    .atView(view.findViewById(R.id.tv_name))
                    .hasShadowBg(false)
                    .isDarkTheme(Utils.isAppDarkTheme()) // 气泡跟随主题(直读 App 主题,防 ROM uiMode 不同步误判浅色)
                    .asAttachList(
                        arrayOf(
                            if (item.isTop) "取消置顶" else "置顶",
                            "重命名",
                            "复制地址"
                        ), null
                    ) { index: Int, _: String? ->
                        when (index) {
                            0 -> {
                                item.isTop = !item.isTop
                                mSubscriptions[position] = item
                                mSubscriptionAdapter.setNewData(mSubscriptions)
                            }
                            1 -> {
                                XPopup.Builder(this)
                                    .asInputConfirm(
                                        "更改为",
                                        "",
                                        item.name,
                                        "新的订阅名",
                                        { text ->
                                            if (!TextUtils.isEmpty(text)) {
                                                if (text.trim { it <= ' ' }.length > 8) {
                                                    AppBubble.toast("不要过长,不方便记忆")
                                                } else {
                                                    item.name = text.trim { it <= ' ' }
                                                    mSubscriptionAdapter.notifyItemChanged(position)
                                                }
                                            }
                                        },
                                        null,
                                        R.layout.dialog_input
                                    ).show()
                            }
                            2 -> {
                                ClipboardUtils.copyText(mSubscriptions.get(position).url)
                                AppBubble.toastLong("已复制")
                            }
                        }
                    }.show()
                true
            }
    }

    /**
     * 订阅列表空态:无订阅时展示空态占位(列表隐藏),否则展示列表
     */
    private fun updateEmptyState() {
        val empty = mSubscriptions.isEmpty()
        mBinding.rv.visibility = if (empty) View.GONE else View.VISIBLE
        mBinding.llEmpty.root.visibility = if (empty) View.VISIBLE else View.GONE
    }

    /**
     * 本地导入(系统 SAF 文件选择器;替代 hedzr 反射 StorageVolume 的老实现):
     * 选择 txt/json 后,主卷文件(clan 服务器可直接按路径读)转真实路径、以 clan:// 引用原文件;
     * 其它存储提供方(下载/云盘/第三方文件管理器等,SAF 授权读取但无主卷路径)复制进应用专属目录后
     * 再以 clan:// 引用副本——任何能在系统文件管理器里打开的文件均可导入。
     * @param checked 是否在导入成功后默认启用该订阅(导入菜单勾选状态,先记录后使用)
     */
    private var mPendingChecked = true
    private val pickLocalDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handleLocalDoc(it) }
    }

    private fun pickFile(checked: Boolean) {
        mPendingChecked = checked
        pickLocalDoc.launch(arrayOf("*/*"))
    }

    private fun handleLocalDoc(uri: Uri) {
        try {
            val nameRaw = queryDisplayName(uri)
            val name = nameRaw?.trim()
            if (name.isNullOrEmpty() ||
                !name.lowercase().endsWith(".txt") && !name.lowercase().endsWith(".json")
            ) {
                AppBubble.toast("请选择 txt/json 订阅文件")
                return
            }
            // 1) 主卷真实路径:clan:// 直接引用原文件(用户后续编辑文件可即时生效)
            var importPath: String? = externalStoragePathOf(uri)
            var importFile = importPath?.let { File(it) }
            if (importFile == null || !importFile.exists()) {
                // 2) 其它提供方无主卷路径:复制到应用专属目录(SAF 授权期内读流;副本由 clan 服务器读取)
                val copy = importCopyOf(uri, name!!)
                if (copy == null) {
                    AppBubble.toast("无法读取所选文件,请选择本机存储中的 txt/json 订阅文件")
                    return
                }
                importFile = copy
                importPath = copy.absolutePath
            }
            if (importPath == null || !isUnderPrimaryStorage(importFile!!)) {
                AppBubble.toast("暂不支持该存储位置,请选择内部存储中的 txt/json 订阅文件")
                return
            }
            // 订阅清单式文件(如 assets/config/default_subscriptions.json: [{name,url},...]):
            // 读取内容解析为多条订阅加入;识别失败则回落为"单个 clan:// 文件源"加入
            if (importSubscriptionList(importFile!!, name)) {
                SubscriptionConfig.setLastImportDir(importFile!!.parent)
                return
            }
            // 记忆导入目录(与旧文件选择器一致:以父目录为准)
            SubscriptionConfig.setLastImportDir(importFile!!.parent)
            val clanPath = "clan://localhost" + importPath.removePrefix("/storage/emulated/0")
            for (item in mSubscriptions) {
                if (item.url == clanPath) {
                    AppBubble.toastLong("订阅地址与" + item.name + "相同")
                    return
                }
            }
            addSubscription(name, clanPath, mPendingChecked)
        } catch (t: Throwable) {
            t.printStackTrace()
            AppLog.log("订阅管理", "本地导入读取失败: " + uri + "  " + t)
            LogStore.fail(Category.SUBSCRIPTION, "订阅: 本地导入读取文件失败")
            AppBubble.toast("读取所选文件失败")
        }
    }

    /** 应用专属导入目录(外部存储根下,clan:// 副本可被本地文件服务器读取,无需额外存储权限) */
    private fun importDir(): File {
        val dir = File(getExternalFilesDir(null), "subscription_import")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 订阅清单文件导入(形如 assets 默认订阅 [{name,url},...]):
     * 读取内容识别为"清单数组"后逐条去重加入(不做网络校验,用户点选启用时才拉取);
     * 识别失败/无有效条目返回 false,由调用方按"单个 clan:// 文件源"回落,不影响原有导入。
     * @return true=已按清单处理(可能 0 条新增);false=非目标格式
     */
    private fun importSubscriptionList(file: File, displayName: String): Boolean {
        val text = try {
            file.readText(Charsets.UTF_8).trim()
        } catch (t: Throwable) {
            return false
        }
        if (text.isEmpty() || text[0] != '[') return false

        val parsed = ArrayList<Subscription>()
        try {
            val arr = org.json.JSONArray(text)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val n = obj.optString("name", "").trim()
                val u = obj.optString("url", "").trim()
                if (n.isNotEmpty() && u.isNotEmpty()) parsed.add(Subscription(n, u))
            }
        } catch (t: Throwable) {
            return false // 非目标格式(如单源配置 JSON 对象),回落旧逻辑
        }
        if (parsed.isEmpty()) return false

        val hadChecked = mSubscriptions.any { it.isChecked }
        var firstAdded: Subscription? = null
        var added = 0
        for (s in parsed) {
            if (mSubscriptions.any { it.url == s.url }) continue // 与本机已有订阅去重
            mSubscriptions.add(s.setChecked(false))
            if (firstAdded == null) firstAdded = s
            added++
        }
        if (added == 0) {
            AppLog.log("订阅管理", "清单导入无新增(全部重复): " + displayName)
            LogStore.log(Category.SUBSCRIPTION, "订阅: 清单导入无新增,地址均与本机重复")
            AppBubble.toastLong("清单中的订阅地址与本机已有订阅相同")
            return true
        }
        // 勾选了"启用"且当前没有使用中的订阅 → 默认启用清单首条,避免导入后无可用的订阅
        if (mPendingChecked && !hadChecked && firstAdded != null) {
            firstAdded.isChecked = true
            mSelectedUrl = firstAdded.url
        }
        AppLog.log("订阅管理", "导入订阅清单: " + added + " 条(文件 " + displayName + ")")
        LogStore.log(Category.SUBSCRIPTION, "订阅: 清单导入 " + added + " 条")
        mSubscriptionAdapter.setNewData(mSubscriptions)
        updateEmptyState()
        AppBubble.toast("已导入 $added 条订阅")
        return true
    }

    /**
     * 把 SAF 选中的文件内容复制进应用专属导入目录。
     * 使用 URI 摘要作为副本名,重复选择同一文件时复用已有副本。
     */
    private fun importCopyOf(uri: Uri, displayName: String): File? {
        return try {
            val base = sanitizeImportName(displayName)
            val dot = base.lastIndexOf('.')
            val stem = if (dot > 0) base.substring(0, dot) else base
            val ext = if (dot > 0) base.substring(dot) else ""
            val target = File(importDir(), stem + "_" + uriDigest(uri) + ext)
            if (target.exists()) return target
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: return null
            target
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }

    private fun uriDigest(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** 清理文件名中不能出现在真实路径的字符,并确保带 txt/json 扩展名 */
    private fun sanitizeImportName(displayName: String): String {
        var n = displayName.replace(Regex("[/\\\\:*?\"<>|\\u0000\\s]"), "_").trim()
        if (!n.lowercase().endsWith(".txt") && !n.lowercase().endsWith(".json")) {
            n += ".txt"
        }
        if (n.length > 80) n = n.substring(0, 80)
        return n
    }

    /** 查询所选文档的显示名(取不到时用 uri 末段兜底) */
    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (t: Throwable) {
            uri.lastPathSegment
        }
    }

    private fun isUnderPrimaryStorage(file: File): Boolean {
        return try {
            val root = Environment.getExternalStorageDirectory().canonicalFile
            val candidate = file.canonicalFile
            candidate == root || candidate.path.startsWith(root.path + File.separator)
        } catch (t: Throwable) {
            false
        }
    }

    /** 支持更多存储提供方转真实路径;主要支持 primary/home 等可被 clan 服务器按路径读取的存储 */
    private fun externalStoragePathOf(uri: Uri): String? {
        if (uri.scheme != "content") return null
        return try {
            val docId = DocumentsContract.getDocumentId(uri)
            val sep = docId.indexOf(':')
            if (sep <= 0) return null

            val volumeName = docId.substring(0, sep)
            val pathWithinVolume = docId.substring(sep + 1)
            if (pathWithinVolume.isEmpty()) return null

            val basePath = when (volumeName) {
                "primary" -> Environment.getExternalStorageDirectory().canonicalPath
                "home" -> Environment.getExternalStorageDirectory().canonicalPath
                else -> return null
            }
            val root = File(basePath).canonicalFile
            val candidate = File(root, pathWithinVolume).canonicalFile
            if (candidate != root && !candidate.path.startsWith(root.path + File.separator)) {
                return null
            }
            candidate.path
        } catch (t: Throwable) {
            null
        }
    }

    private fun addSubscription(name: String, url: String, checked: Boolean) {
        if (url.startsWith("clan://")) {
            LogStore.log(Category.SUBSCRIPTION, "订阅: 新增 " + name + "(clan)")
            addSub2List(name, url, checked)
            mSubscriptionAdapter.setNewData(mSubscriptions)
            updateEmptyState()
        } else if (url.startsWith("http")) {
            showLoadingDialog()
            AppLog.log("订阅管理", "新增订阅: " + name + "  " + url)
            LogStore.log(Category.SUBSCRIPTION, "订阅: 新增 " + name)
            HttpClient.get(url, null, "get_subscription", object : HCallBack {
                    override fun onSuccess(response: String) {
                        dismissLoadingDialog()
                        try {
                            val json = JsonParser.parseString(response).asJsonObject
                            // 多线路?
                            val urls = json["urls"]
                            // 多仓?
                            val storeHouse = json["storeHouse"]
                            if (urls != null && urls.isJsonArray) { // 多线路
                                if (checked) {
                                    AppBubble.toastLong("多条线路请主动选择")
                                }
                                val urlList = urls.asJsonArray
                                if (urlList != null && urlList.size() > 0 && urlList[0].isJsonObject
                                    && urlList[0].asJsonObject.has("url")
                                    && urlList[0].asJsonObject.has("name")
                                ) { //多线路格式
                                    for (i in 0 until urlList.size()) {
                                        val obj = urlList[i] as JsonObject
                                        val name = obj["name"].asString.trim { it <= ' ' }
                                            .replace("<|>|《|》|-".toRegex(), "")
                                        val url = obj["url"].asString.trim { it <= ' ' }
                                        mSubscriptions.add(Subscription(name, url))
                                    }
                                }
                            } else if (storeHouse != null && storeHouse.isJsonArray) { // 多仓
                                val storeHouseList = storeHouse.asJsonArray
                                if (storeHouseList != null && storeHouseList.size() > 0 && storeHouseList[0].isJsonObject
                                    && storeHouseList[0].asJsonObject.has("sourceName")
                                    && storeHouseList[0].asJsonObject.has("sourceUrl")
                                ) { //多仓格式
                                    mSources.clear()
                                    for (i in 0 until storeHouseList.size()) {
                                        val obj = storeHouseList[i] as JsonObject
                                        val name = obj["sourceName"].asString.trim { it <= ' ' }
                                            .replace("<|>|《|》|-".toRegex(), "")
                                        val url = obj["sourceUrl"].asString.trim { it <= ' ' }
                                        mSources.add(Source(name, url))
                                    }
                                    XPopup.Builder(this@SubscriptionActivity)
                                        .asCustom(
                                            ChooseSourceDialog(
                                                this@SubscriptionActivity,
                                                mSources
                                            ) { position: Int, _: String? ->
                                                // 再根据多线路格式获取配置,如果仓内是正常多线路模式,name没用,直接使用线路的命名
                                                addSubscription(
                                                    mSources[position].sourceName,
                                                    mSources[position].sourceUrl,
                                                    checked
                                                )
                                            })
                                        .show()
                                }
                            } else { // 单线路/其余
                                addSub2List(name, url, checked)
                            }
                        } catch (th: Throwable) {
                            addSub2List(name, url, checked)
                        }
                        mSubscriptionAdapter.setNewData(mSubscriptions)
                        updateEmptyState()
                    }

                    override fun onError(e: Throwable) {
                        dismissLoadingDialog()
                        AppLog.log("订阅管理", "新增订阅失败: " + name + "  " + url + "  " + e)
                        LogStore.fail(Category.SUBSCRIPTION, "订阅: 新增订阅失败 " + name + " 网络错误/地址无效")
                        AppBubble.toastLong("订阅失败,请检查地址或网络状态")
                    }
                })
        } else {
            AppBubble.toast("订阅格式不正确")
        }
    }

    /**
     * 仅当选中本地文件和添加的为单线路时,使用此订阅生效。多线路会直接解析全部并添加,多仓会展开并选择,最后也按多线路处理,直接添加
     * @param name
     * @param url
     * @param checkNewest
     */
    private fun addSub2List(name: String, url: String, checkNewest: Boolean) {
        if (checkNewest) { //选中最新的,清除以前的选中订阅
            for (subscription in mSubscriptions) {
                if (subscription.isChecked) {
                    subscription.setChecked(false)
                }
            }
            mSelectedUrl = url
            mSubscriptions.add(Subscription(name, url).setChecked(true))
        } else {
            mSubscriptions.add(Subscription(name, url).setChecked(false))
        }
    }

    override fun onPause() {
        super.onPause()
        // 更新缓存
        SubscriptionConfig.setApiUrl(mSelectedUrl)
        SubscriptionConfig.setSubscriptions(mSubscriptions)
    }

    override fun finish() {
        //切换了订阅地址
        if (!TextUtils.isEmpty(mSelectedUrl) && mBeforeUrl != mSelectedUrl) {
            val intent = Intent(this, MainActivity::class.java)
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        super.finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        HttpClient.cancel("get_subscription")
    }
}