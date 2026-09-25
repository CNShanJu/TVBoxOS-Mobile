package com.github.tvbox.osc.ui.activity

import android.os.Build
import android.os.Process
import android.view.MenuItem
import android.view.ViewGroup
import androidx.appcompat.widget.TooltipCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener
import com.blankj.utilcode.util.ActivityUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.util.AppBubble
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.databinding.ActivityMainBinding
import com.github.tvbox.osc.ui.fragment.GridFragment
import com.github.tvbox.osc.ui.fragment.HomeFragment
import com.github.tvbox.osc.ui.fragment.MyFragment
import kotlin.system.exitProcess

class MainActivity : BaseVbActivity<ActivityMainBinding>() {

    var fragments = listOf(HomeFragment(), MyFragment())
    var useCacheConfig = false
    private var exitTime = 0L

    override fun init() {

        useCacheConfig = intent.extras?.getBoolean(IntentKey.CACHE_CONFIG_CHANGED, false)?:false

        mBinding.vp.adapter = object : FragmentPagerAdapter(supportFragmentManager) {
            override fun getItem(position: Int): Fragment {
                return fragments[position]
            }

            override fun getCount(): Int {
                return fragments.size
            }
        }

        mBinding.bottomNav.setOnNavigationItemSelectedListener { menuItem: MenuItem ->
            mBinding.vp.setCurrentItem(menuItem.order, false)
            updateNavIcons(menuItem.order)
            true
        }
        // 底栏图标不参与长按:Material 的 NavigationBarItemView 会给每个条目挂 Tooltip
        // (TooltipCompat.setTooltipText,文本取"条目标题"—— 只把文本置空也会回退成标题),那个提示
        // 用系统样式(与主题反色)、位置固定在锚点上方偏右,主题里改不了。本底栏 labelVisibilityMode=labeled,
        // 图标下方一直显示文字,长按提示本就多余 —— 这里直接清掉条目上的 Tooltip 与长按处理:
        // TooltipCompat 置空时会顺带移除长按监听并关掉 longClickable(API<26 的 AppCompat 路径),
        // API≥26 走 framework 的 setTooltipText(null),所以监听与 longClickable 这里再显式清一遍。
        mBinding.bottomNav.post {
            for (i in 0 until mBinding.bottomNav.childCount) {
                val item = mBinding.bottomNav.getChildAt(i)
                item.setOnLongClickListener(null)
                item.isLongClickable = false
                TooltipCompat.setTooltipText(item, null)
            }
        }
        mBinding.vp.addOnPageChangeListener(object : SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                mBinding.bottomNav.menu.getItem(position).setChecked(true)
                updateNavIcons(position)
            }
        })
        updateNavIcons(0)
    }

    /** 底部导航图标: 选中项换"选中"变体(与未选中图形区分), 颜色仍由 itemIconTint 按状态着色 */
    private fun updateNavIcons(position: Int) {
        val menu = mBinding.bottomNav.menu
        if (menu.size() >= 2) {
            menu.getItem(0).setIcon(
                if (position == 0) R.drawable.ic_nav_home_sel else R.drawable.ic_nav_home
            )
            menu.getItem(1).setIcon(
                if (position == 1) R.drawable.ic_nav_my_sel else R.drawable.ic_nav_my
            )
        }
    }

    override fun onBackPressed() {
        if (mBinding.vp.currentItem != 0) { // 非首页(我的)按返回回首页
            mBinding.vp.currentItem = 0
            return
        }
        val homeFragment = fragments[0] as HomeFragment
        if (!homeFragment.isAdded) { // 资源不足销毁重建时未挂载到activity时getChildFragmentManager会崩溃
            confirmExit()
            return
        }
        val childFragments = homeFragment.allFragments
        if (childFragments.isEmpty()) { //加载中(没有tab)
            confirmExit()
            return
        }
        val fragment: Fragment = childFragments[homeFragment.tabIndex]
        if (fragment is GridFragment) { // 首页数据源动态加载的tab
            if (!fragment.restoreView()) { // 有回退的view,先回退(AList等文件夹列表),没有可回退的,返到主页tab
                if (!homeFragment.scrollToFirstTab()) {
                    confirmExit()
                }
            }
        } else {
            confirmExit()
        }
    }

    private fun confirmExit() {
        if (System.currentTimeMillis() - exitTime > 2000) {
            AppBubble.toast("再按一次退出程序")
            exitTime = System.currentTimeMillis()
        } else {
            ActivityUtils.finishAllActivities(true)
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }
    }
}