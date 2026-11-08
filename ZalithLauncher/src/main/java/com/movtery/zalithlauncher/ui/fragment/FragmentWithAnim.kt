package com.movtery.zalithlauncher.ui.fragment

import com.movtery.anim.AnimPlayer
import com.movtery.zalithlauncher.utils.anim.SlideAnimation

/** Navigation owns one short fade. Do not also bounce every child of the incoming page. */
abstract class FragmentWithAnim : BaseFragment, SlideAnimation {
    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    // Retain the existing API for callers; transitions belong to FragmentTransaction.
    fun slideIn() { view?.animate()?.cancel(); view?.alpha = 1f }
    fun slideOut() { view?.animate()?.cancel(); view?.alpha = 1f }

    override fun onDestroyView() {
        view?.animate()?.cancel()
        view?.clearAnimation()
        super.onDestroyView()
    }
}
