package com.movtery.zalithlauncher.utils.anim

import android.animation.ValueAnimator
import com.movtery.zalithlauncher.setting.AllSettings

object MotionPolicy {
    @JvmStatic
    fun isEnabled(): Boolean = AllSettings.animation.getValue() && ValueAnimator.areAnimatorsEnabled()
}
