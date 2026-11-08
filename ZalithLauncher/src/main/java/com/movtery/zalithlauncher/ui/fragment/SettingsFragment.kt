package com.movtery.zalithlauncher.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.movtery.anim.AnimPlayer
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.databinding.FragmentSettingsBinding
import com.movtery.zalithlauncher.event.value.SettingsPageSwapEvent
import com.movtery.zalithlauncher.setting.Settings
import com.movtery.zalithlauncher.ui.fragment.settings.*
import org.greenrobot.eventbus.EventBus

class SettingsFragment : FragmentWithAnim(R.layout.fragment_settings) {
    companion object { const val TAG = "SettingsFragment" }
    private var currentBinding: FragmentSettingsBinding? = null
    private val binding get() = checkNotNull(currentBinding)
    private var callback: ViewPager2.OnPageChangeCallback? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        currentBinding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val tabs = listOf(binding.tabVideo, binding.tabControl, binding.tabGame,
            binding.tabLauncher, binding.tabExperimental)
        binding.settingsViewpager.apply {
            adapter = ViewPagerAdapter(this@SettingsFragment)
            offscreenPageLimit = 1
            isUserInputEnabled = false
            callback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    val current = currentBinding ?: return
                    tabs.forEachIndexed { index, tab -> tab.isSelected = index == position }
                    tabs[position].post {
                        if (currentBinding === current) tabs[position].requestRectangleOnScreen(
                            android.graphics.Rect(0, 0, tabs[position].width, tabs[position].height), true)
                    }
                    EventBus.getDefault().post(SettingsPageSwapEvent(position))
                }
            }
            registerOnPageChangeCallback(callback!!)
            tabs.forEachIndexed { index, tab -> tab.setOnClickListener { setCurrentItem(index, false) } }
            setCurrentItem(savedInstanceState?.getInt("settings_page", 3) ?: 3, false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        currentBinding?.let { outState.putInt("settings_page", it.settingsViewpager.currentItem) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() { super.onResume(); Settings.refreshSettings() }

    override fun onDestroyView() {
        callback?.let { binding.settingsViewpager.unregisterOnPageChangeCallback(it) }
        binding.settingsViewpager.adapter = null
        callback = null
        currentBinding = null
        super.onDestroyView()
    }

    override fun slideIn(animPlayer: AnimPlayer) = Unit
    override fun slideOut(animPlayer: AnimPlayer) = Unit

    // Scope pages to this fragment, not its activity; avoids retaining detached settings pages.
    private class ViewPagerAdapter(val fragment: FragmentWithAnim) : FragmentStateAdapter(fragment) {
        override fun getItemCount() = 5
        override fun createFragment(position: Int): Fragment = when (position) {
            1 -> ControlSettingsFragment(fragment)
            2 -> GameSettingsFragment()
            3 -> LauncherSettingsFragment(fragment)
            4 -> ExperimentalSettingsFragment()
            else -> VideoSettingsFragment()
        }
    }
}
