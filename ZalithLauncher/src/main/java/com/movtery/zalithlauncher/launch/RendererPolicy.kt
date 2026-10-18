package com.movtery.zalithlauncher.launch

import android.app.Activity
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.movtery.zalithlauncher.R
import com.movtery.zalithlauncher.feature.MCOptions
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.version.Version
import com.movtery.zalithlauncher.renderer.RendererInterface
import com.movtery.zalithlauncher.renderer.Renderers
import com.movtery.zalithlauncher.renderer.renderers.KryptonRenderer
import com.movtery.zalithlauncher.renderer.renderers.LTWRenderer
import com.movtery.zalithlauncher.ui.dialog.LifecycleAwareTipDialog
import com.movtery.zalithlauncher.ui.dialog.TipDialog
import com.movtery.zalithlauncher.utils.ZHTools
import net.kdt.pojavlaunch.JMinecraftVersionList
import net.kdt.pojavlaunch.Logger
import net.kdt.pojavlaunch.MinecraftGLSurface
import net.kdt.pojavlaunch.Tools
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale

/**
 * Makes sure the renderer can run the selected Minecraft version, with the rules MojoLauncher uses
 * (git.artdeell.mojo.utils.jre.GameRunner):
 * - GL4ES can't run versions released from 2025-02-07 on (1.21.5 snapshots and newer, all of 26.x),
 *   and Sodium (or a fork) on 1.17+ needs desktop OpenGL 3 features: use LTW instead.
 * - Krypton Wrapper can't run versions released from 26.3-snapshot-3 on: use LTW instead.
 * - LTW can't run versions released before 21w10a (before 1.17, OpenGL compatibility profile) unless
 *   Angelica is installed: use GL4ES instead.
 * - On Adreno GPUs, versions before 1.21.5 create more GL buffers than the driver allows when the render
 *   distance is above 7 (unless Sodium is installed): warn and lower it to 7.
 * A switch only applies to the current launch; the version's renderer setting is not changed.
 */
object RendererPolicy {
    private const val TAG = "RendererPolicy"
    private const val GL4ES_ID = "opengles2"

    /** First day of the OpenGL 3.2 core profile versions (21w10a was released on 2021-03-10). */
    private val CORE_PROFILE_START: Date = date(2021, Calendar.MARCH, 9)
    /** Versions released from this day on can't be run by GL4ES. */
    private val GL4ES_END: Date = date(2025, Calendar.FEBRUARY, 7)
    /** Krypton Wrapper can't run versions released from this day on (26.3-snapshot-3, 2026-07-07). */
    private val KRYPTON_END: Date = date(2026, Calendar.JULY, 7)
    /** 1.21.5 (released on 2025-03-25) no longer runs into the Adreno buffer limit. */
    private val ADRENO_BUFFER_FIX: Date = date(2025, Calendar.MARCH, 25)
    private const val ADRENO_SAFE_RENDER_DISTANCE = 7

    /**
     * @return false if the launch must be aborted (the activity was destroyed while a dialog was shown)
     */
    fun apply(activity: AppCompatActivity, version: Version): Boolean {
        val releaseDate = originalReleaseDate(version) ?: run {
            Logging.w(TAG, "Unknown release date of ${version.getVersionName()}, renderer left as selected")
            return true
        }
        val gameDir = version.getGameDir()
        val hasSodium = version.modCheckResult?.hasSodiumOrEmbeddium == true ||
                hasMod(gameDir, "sodium", "embeddium", "rubidium")
        val compatible = Renderers.getCompatibleRenderers(activity).second
        val ltw = compatible.find { it.getRendererId() == LTWRenderer.ID }
        val gl4es = compatible.find { it.getRendererId() == GL4ES_ID }
        val current = Renderers.getCurrentRenderer()
        val versionName = version.getVersionName()

        // Minecraft 26.3+ (SDL3): SDL creates the EGL context itself, which among the launcher's renderers
        // only LTW supports (through libltw_sdl_egl.so, see SdlSupport)
        if (MinecraftGLSurface.sdlVersion) {
            if (current.getRendererId() != LTWRenderer.ID) {
                if (ltw != null) {
                    switchTo(activity, ltw, activity.getString(R.string.renderer_policy_sdl_use_ltw, versionName))
                } else {
                    val message = activity.getString(R.string.renderer_policy_ltw_unsupported, versionName)
                    Logger.appendToLog("Warning: $message")
                    showToast(activity, message)
                }
            }
            return true
        }

        when (current.getRendererId()) {
            KryptonRenderer.ID -> {
                // Krypton Wrapper doesn't work from 26.3-snapshot-3 on (Amethyst 1.1.7 release notes)
                if (!releaseDate.before(KRYPTON_END) && ltw != null) {
                    switchTo(activity, ltw, activity.getString(R.string.renderer_policy_use_ltw, versionName))
                }
            }
            LTWRenderer.ID -> {
                if (releaseDate.before(CORE_PROFILE_START) && !hasMod(gameDir, "angelica") && gl4es != null) {
                    switchTo(activity, gl4es, activity.getString(R.string.renderer_policy_use_gl4es, versionName))
                }
            }
            GL4ES_ID -> {
                val needsLtw = !releaseDate.before(GL4ES_END) || (!releaseDate.before(CORE_PROFILE_START) && hasSodium)
                if (needsLtw) {
                    if (ltw != null) {
                        switchTo(activity, ltw, activity.getString(R.string.renderer_policy_use_ltw, versionName))
                    } else {
                        val message = activity.getString(R.string.renderer_policy_ltw_unsupported, versionName)
                        Logger.appendToLog("Warning: $message")
                        showToast(activity, message)
                    }
                }
            }
        }

        if (Renderers.getCurrentRenderer().getRendererId() == LTWRenderer.ID &&
            releaseDate.before(ADRENO_BUFFER_FIX) && !hasSodium) {
            return checkAdrenoRenderDistance(activity)
        }
        return true
    }

    private fun checkAdrenoRenderDistance(activity: AppCompatActivity): Boolean {
        val renderDistance = MCOptions.get("renderDistance")?.toIntOrNull() ?: 12 // Minecraft's default
        if (renderDistance <= ADRENO_SAFE_RENDER_DISTANCE || !ZHTools.isAdrenoGPU()) return true

        val builder = TipDialog.Builder(activity)
            .setTitle(R.string.generic_warning)
            .setMessage(R.string.ltw_render_distance_warning_msg)
            .setWarning()
            .setCenterMessage(false)
            .setShowCancel(false)
        if (LifecycleAwareTipDialog.haltOnDialog(activity.lifecycle, builder)) return false
        runCatching {
            MCOptions.set("renderDistance", ADRENO_SAFE_RENDER_DISTANCE.toString())
            MCOptions.save()
            Logger.appendToLog("Info: render distance lowered to $ADRENO_SAFE_RENDER_DISTANCE (Adreno GL buffer limit with LTW)")
        }.onFailure { Logging.e(TAG, "Failed to lower the render distance", it) }
        return true
    }

    private fun switchTo(activity: Activity, renderer: RendererInterface, message: String) {
        Renderers.setCurrentRenderer(activity, renderer.getUniqueIdentifier())
        Logger.appendToLog("Info: $message")
        showToast(activity, message)
    }

    private fun showToast(activity: Activity, message: String) {
        activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
    }

    private fun hasMod(gameDir: File, vararg names: String): Boolean {
        val mods = File(gameDir, "mods").listFiles { file -> file.isFile && file.name.endsWith(".jar") } ?: return false
        return mods.any { mod -> names.any { mod.name.contains(it, ignoreCase = true) } }
    }

    /**
     * The release date of the vanilla version, also for mod loader versions: their own JSON carries the
     * date of the loader profile, and Tools.getVersionInfo() copies that date over the inherited one.
     */
    /** Release date of the Minecraft version itself (for modded versions: of the version they are based on). */
    internal fun originalReleaseDate(version: Version): Date? = try {
        val own = Tools.getVersionInfo(version, true)
        val parent = own.inheritsFrom?.takeIf { it.isNotBlank() && it != own.id }
        val releaseTime = if (parent != null) {
            Tools.GLOBAL_GSON.fromJson(
                Tools.read(version.getVersionsFolder() + "/" + parent + "/" + parent + ".json"),
                JMinecraftVersionList.Version::class.java
            ).releaseTime
        } else own.releaseTime
        releaseTime?.substringBefore('T')?.let { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(it) }
    } catch (e: Exception) {
        Logging.w(TAG, "Failed to read the release date of ${version.getVersionName()}: $e")
        null
    }

    private fun date(year: Int, month: Int, day: Int): Date = GregorianCalendar(year, month, day).time
}
