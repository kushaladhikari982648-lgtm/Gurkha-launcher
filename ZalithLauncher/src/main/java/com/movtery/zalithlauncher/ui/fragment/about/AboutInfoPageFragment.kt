package com.movtery.zalithlauncher.ui.fragment.about

import android.annotation.SuppressLint
import android.content.res.Resources
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.movtery.zalithlauncher.InfoCenter
import com.movtery.zalithlauncher.InfoDistributor
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.databinding.FragmentAboutInfoPageBinding
import com.movtery.zalithlauncher.ui.dialog.TipDialog
import com.movtery.zalithlauncher.ui.subassembly.about.AboutItemBean
import com.movtery.zalithlauncher.ui.subassembly.about.AboutItemBean.AboutItemButtonBean
import com.movtery.zalithlauncher.ui.subassembly.about.AboutRecyclerAdapter
import com.movtery.zalithlauncher.utils.ZHTools
import com.movtery.zalithlauncher.utils.path.UrlManager

class AboutInfoPageFragment() : Fragment(R.layout.fragment_about_info_page) {
    companion object {
        /** Whether to show the upstream (Zalith Launcher) QQ group and Discord buttons. */
        private const val SHOW_UPSTREAM_COMMUNITY = false
    }

    private lateinit var binding: FragmentAboutInfoPageBinding
    private val mAboutData: MutableList<AboutItemBean> = ArrayList()
    private var parentPager2: ViewPager2? = null

    constructor(parentPager: ViewPager2): this() {
        this.parentPager2 = parentPager
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentAboutInfoPageBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        loadAboutData(requireContext().resources)

        val context = requireActivity()

        binding.apply {
            dec1.text = InfoCenter.replaceName(context, R.string.about_dec1)
            dec2.text = InfoCenter.replaceName(context, R.string.about_dec2)
            dec3.text = InfoCenter.replaceName(context, R.string.about_dec3)

            githubButton.setOnClickListener { ZHTools.openLink(requireActivity(), UrlManager.URL_HOME) }
            licenseButton.setOnClickListener { ZHTools.openLink(requireActivity(), "https://www.gnu.org/licenses/gpl-3.0.html") }

            val aboutAdapter = AboutRecyclerAdapter(this@AboutInfoPageFragment.mAboutData)
            aboutRecycler.apply {
                layoutManager = LinearLayoutManager(requireContext())
                adapter = aboutAdapter
            }
            //The sponsor list, the QQ group and the Discord server all belong to Zalith Launcher, not to this
            //fork, so they are hidden. Set SHOW_UPSTREAM_COMMUNITY to true to bring the QQ/Discord buttons back
            //(e.g. after pointing them at your own community).
            sponsor.visibility = View.GONE
            sponsor.setOnClickListener { _ ->
                parentPager2?.currentItem = 1
            }

            if (SHOW_UPSTREAM_COMMUNITY && ZHTools.isChinese(requireActivity())) {
                qqGroupButton.visibility = View.VISIBLE
                qqGroupButton.setOnClickListener {
                    TipDialog.Builder(context)
                        .setTitle("QQ")
                        .setMessage("欢迎加入 ${InfoDistributor.APP_NAME} 官方 QQ 交流群（群号：${InfoCenter.QQ_GROUP}）！由于群人数有限，加入群聊前需要赞助 5元 或以上金额，请点击右侧“赞助开发”按钮访问爱发电。")
                        .setSelectable(true)
                        .setConfirm(R.string.generic_confirm)
                        .setShowCancel(false)
                        .showDialog()
                }
            } else {
                qqGroupButton.visibility = View.GONE
            }

            discordButton.visibility = if (SHOW_UPSTREAM_COMMUNITY) View.VISIBLE else View.GONE
            discordButton.setOnClickListener { ZHTools.openLink(requireActivity(), "https://discord.gg/yDDkTHp4cJ") }
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun loadAboutData(resources: Resources) {
        mAboutData.clear()

        //Gurkha Launcher is a fork of Zalith Launcher: keep crediting it
        mAboutData.add(
            AboutItemBean(
                resources.getDrawable(R.drawable.image_about_zalith, requireContext().theme),
                "Zalith Launcher",
                InfoCenter.replaceName(requireContext(), R.string.about_zalith_desc),
                AboutItemButtonBean(requireActivity(), "Github", UrlManager.URL_UPSTREAM)
            )
        )
        mAboutData.add(
            AboutItemBean(
                resources.getDrawable(R.drawable.ic_pojav_full, requireContext().theme),
                "PojavLauncherTeam",
                getString(R.string.about_PojavLauncher_desc),
                AboutItemButtonBean(requireActivity(), "Github", "https://github.com/PojavLauncherTeam/PojavLauncher")
            )
        )
        mAboutData.add(
            AboutItemBean(
                resources.getDrawable(R.drawable.image_about_movtery, requireContext().theme),
                "墨北MovTery",
                getString(R.string.about_MovTery_desc),
                AboutItemButtonBean(
                    requireActivity(),
                    getString(R.string.about_access_space),
                    "https://space.bilibili.com/2008204513"
                )
            )
        )
        mAboutData.add(
            AboutItemBean(
                resources.getDrawable(R.drawable.image_about_mcmod, requireContext().theme),
                "MC 百科",
                getString(R.string.about_mcmod_desc),
                AboutItemButtonBean(
                    requireActivity(),
                    getString(R.string.about_access_link),
                    UrlManager.URL_MCMOD)
            )
        )
        mAboutData.add(
            AboutItemBean(
                resources.getDrawable(R.drawable.image_about_verafirefly, requireContext().theme),
                "Vera-Firefly",
                getString(R.string.about_VeraFirefly_desc),
                AboutItemButtonBean(
                    requireActivity(),
                    getString(R.string.about_access_space),
                    "https://space.bilibili.com/1412062866"
                )
            )
        )
        mAboutData.add(
            AboutItemBean(
                resources.getDrawable(R.drawable.image_about_lingmuqiuzhu, requireContext().theme),
                "柃木湫竹",
                getString(R.string.about_LingMuQiuZhu_desc),
                AboutItemButtonBean(
                    requireActivity(),
                    getString(R.string.about_access_space),
                    "https://space.bilibili.com/515165764"
                )
            )
        )
        mAboutData.add(
            AboutItemBean(
                resources.getDrawable(R.drawable.image_about_shirosakimio, requireContext().theme),
                "ShirosakiMio",
                getString(R.string.about_ShirosakiMio_desc),
                AboutItemButtonBean(
                    requireActivity(),
                    getString(R.string.about_access_space),
                    "https://space.bilibili.com/35801833"
                )
            )
        )
        mAboutData.add(
            AboutItemBean(
                resources.getDrawable(R.drawable.image_about_bangbang93, requireContext().theme),
                "bangbang93",
                getString(R.string.about_bangbang93_desc),
                AboutItemButtonBean(
                    requireActivity(),
                    getString(R.string.about_button_support_development),
                    "https://afdian.com/a/bangbang93"
                )
            )
        )
    }
}

