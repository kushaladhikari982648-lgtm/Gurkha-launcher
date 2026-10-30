package net.kdt.pojavlaunch.fragments;

import static com.movtery.zalithlauncher.event.single.RefreshVersionsEvent.MODE.END;

import android.os.Bundle;
import android.graphics.Color;
import androidx.constraintlayout.widget.ConstraintSet;
import com.movtery.zalithlauncher.setting.AllSettings;
import com.movtery.zalithlauncher.renderer.Renderers;
import com.movtery.zalithlauncher.feature.background.BackgroundManager;
import com.movtery.zalithlauncher.feature.background.BackgroundType;
import com.movtery.zalithlauncher.ui.fragment.ModsFragment;
import com.movtery.zalithlauncher.ui.fragment.SettingsFragment;
import java.io.File;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.movtery.anim.AnimPlayer;
import com.movtery.anim.animations.Animations;
import com.movtery.zalithlauncher.InfoCenter;
import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.databinding.FragmentLauncherBinding;
import com.movtery.zalithlauncher.event.single.AccountUpdateEvent;
import com.movtery.zalithlauncher.event.single.LaunchGameEvent;
import com.movtery.zalithlauncher.event.single.RefreshVersionsEvent;
import com.movtery.zalithlauncher.feature.version.Version;
import com.movtery.zalithlauncher.feature.version.utils.VersionIconUtils;
import com.movtery.zalithlauncher.feature.version.VersionInfo;
import com.movtery.zalithlauncher.feature.version.VersionsManager;
import com.movtery.zalithlauncher.task.TaskExecutors;
import com.movtery.zalithlauncher.ui.fragment.AboutFragment;
import com.movtery.zalithlauncher.ui.fragment.ControlButtonFragment;
import com.movtery.zalithlauncher.ui.fragment.FilesFragment;
import com.movtery.zalithlauncher.ui.fragment.FragmentWithAnim;
import com.movtery.zalithlauncher.ui.fragment.VersionManagerFragment;
import com.movtery.zalithlauncher.ui.fragment.VersionsListFragment;
import com.movtery.zalithlauncher.ui.subassembly.account.AccountViewWrapper;
import com.movtery.zalithlauncher.utils.path.PathManager;
import com.movtery.zalithlauncher.utils.ZHTools;
import com.movtery.zalithlauncher.utils.anim.ViewAnimUtils;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class MainMenuFragment extends FragmentWithAnim {
    public static final String TAG = "MainMenuFragment";
    private FragmentLauncherBinding binding;
    private AccountViewWrapper accountViewWrapper;

    public MainMenuFragment() {
        super(R.layout.fragment_launcher);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentLauncherBinding.inflate(getLayoutInflater());
        // Keep the shared account widget unchanged on the account-management screen.
        compactHomeAccount();
        binding.overworldArt.setVisibility(BackgroundManager.hasBackgroundImage(BackgroundType.MAIN_MENU)
                ? View.GONE : View.VISIBLE);
        accountViewWrapper = new AccountViewWrapper(this, binding.viewAccount);
        accountViewWrapper.refreshAccountInfo();
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        binding.aboutText.setText(InfoCenter.replaceName(requireActivity(), R.string.about_tab));
        binding.aboutButton.setOnClickListener(v -> ZHTools.swapFragmentWithAnim(this, AboutFragment.class, AboutFragment.TAG, null));
        binding.customControlButton.setOnClickListener(v -> ZHTools.swapFragmentWithAnim(this, ControlButtonFragment.class, ControlButtonFragment.TAG, null));
        binding.openMainDirButton.setOnClickListener(v -> {
            Bundle bundle = new Bundle();
            bundle.putString(FilesFragment.BUNDLE_LIST_PATH, PathManager.DIR_GAME_HOME);
            ZHTools.swapFragmentWithAnim(this, FilesFragment.class, FilesFragment.TAG, bundle);
        });
        binding.installJarButton.setOnClickListener(v -> runInstallerWithConfirmation(false));
        binding.installJarButton.setOnLongClickListener(v -> {
            runInstallerWithConfirmation(true);
            return true;
        });
        binding.shareLogsButton.setOnClickListener(v -> ZHTools.shareLogs(requireActivity()));

        binding.version.setOnClickListener(v -> {
            if (!isTaskRunning()) {
                ZHTools.swapFragmentWithAnim(this, VersionsListFragment.class, VersionsListFragment.TAG, null);
            } else {
                ViewAnimUtils.setViewAnim(binding.version, Animations.Shake);
                TaskExecutors.runInUIThread(() -> Toast.makeText(requireContext(), R.string.version_manager_task_in_progress, Toast.LENGTH_SHORT).show());
            }
        });
        binding.managerProfileButton.setOnClickListener(v -> {
            if (!isTaskRunning()) {
                ViewAnimUtils.setViewAnim(binding.managerProfileButton, Animations.Pulse);
                ZHTools.swapFragmentWithAnim(this, VersionManagerFragment.class, VersionManagerFragment.TAG, null);
            } else {
                ViewAnimUtils.setViewAnim(binding.managerProfileButton, Animations.Shake);
                TaskExecutors.runInUIThread(() -> Toast.makeText(requireContext(), R.string.version_manager_task_in_progress, Toast.LENGTH_SHORT).show());
            }
        });

        binding.versionsButton.setOnClickListener(v -> binding.version.performClick());
        binding.settingsButton.setOnClickListener(v -> ZHTools.swapFragmentWithAnim(this, SettingsFragment.class, SettingsFragment.TAG, null));
        binding.modsButton.setOnClickListener(v -> {
            Version selected = VersionsManager.INSTANCE.getCurrentVersion();
            if (selected == null || isTaskRunning()) {
                Toast.makeText(requireContext(), selected == null ? R.string.version_no_versions
                        : R.string.version_manager_task_in_progress, Toast.LENGTH_SHORT).show();
                return;
            }
            Bundle bundle = new Bundle();
            bundle.putString(ModsFragment.BUNDLE_ROOT_PATH, new File(selected.getGameDir(), "mods").getAbsolutePath());
            ZHTools.swapFragmentWithAnim(this, ModsFragment.class, ModsFragment.TAG, bundle);
        });

        binding.playButton.setOnClickListener(v -> EventBus.getDefault().post(new LaunchGameEvent()));

        // No always-running text marquee on the low-end home screen.
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshCurrentVersion();
    }

    private void compactHomeAccount() {
        ConstraintSet set = new ConstraintSet();
        set.clone(binding.viewAccount.getRoot());
        int avatar = R.id.user_icon, name = R.id.user_name, type = R.id.account_type;
        for (int id : new int[]{avatar, name, type}) set.clear(id);
        int size = (int) Tools.dpToPx(40);
        int gap = (int) Tools.dpToPx(8);
        set.constrainWidth(avatar, size);
        set.constrainHeight(avatar, size);
        set.connect(avatar, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, gap);
        set.connect(avatar, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP);
        set.connect(avatar, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM);
        for (int id : new int[]{name, type}) {
            set.constrainWidth(id, ConstraintSet.MATCH_CONSTRAINT);
            set.constrainHeight(id, ConstraintSet.WRAP_CONTENT);
            set.connect(id, ConstraintSet.START, avatar, ConstraintSet.END, gap);
            set.connect(id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, gap);
        }
        set.connect(name, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, gap);
        set.connect(type, ConstraintSet.TOP, name, ConstraintSet.BOTTOM, gap / 2);
        set.applyTo(binding.viewAccount.getRoot());
        binding.viewAccount.getRoot().setBackgroundResource(R.drawable.mc_panel);
        binding.viewAccount.userName.setTextColor(Color.rgb(243, 242, 232));
        binding.viewAccount.accountType.setTextColor(Color.rgb(196, 203, 185));
        binding.viewAccount.userName.setTextSize(16);
        binding.viewAccount.accountType.setTextSize(12);
        binding.viewAccount.userName.setSingleLine(true);
        binding.viewAccount.userName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        binding.viewAccount.accountType.setSingleLine(true);
        binding.viewAccount.accountType.setEllipsize(android.text.TextUtils.TruncateAt.END);
    }

    private void refreshCurrentVersion() {
        if (binding == null) return;
        Version version = VersionsManager.INSTANCE.getCurrentVersion();
        String renderer = version != null ? version.getRenderer() : AllSettings.getRenderer().getValue();
        binding.rendererSummary.setText(getString(R.string.mc_renderer, Renderers.INSTANCE.getDisplayName(renderer)));
        binding.memorySummary.setText(getString(R.string.mc_memory, AllSettings.getRamAllocation().getValue().getValue()));
        binding.lowEndStatus.setText(AllSettings.getLowEndMode().getValue() ? R.string.mc_low_end_on : R.string.mc_low_end_off);
        binding.modsButton.setEnabled(version != null);

        int versionInfoVisibility;
        if (version != null) {
            binding.versionName.setText(version.getVersionName());
            VersionInfo versionInfo = version.getVersionInfo();
            if (versionInfo != null) {
                binding.versionInfo.setText(versionInfo.getInfoString());
                versionInfoVisibility = View.VISIBLE;
            } else versionInfoVisibility = View.GONE;

            new VersionIconUtils(version).start(binding.versionIcon);
            binding.managerProfileButton.setVisibility(View.VISIBLE);
        } else {
            binding.versionName.setText(R.string.version_no_versions);
            binding.managerProfileButton.setVisibility(View.GONE);
            versionInfoVisibility = View.GONE;
        }
        binding.versionInfo.setVisibility(versionInfoVisibility);
    }

    @Subscribe()
    public void event(RefreshVersionsEvent event) {
        if (event.getMode() == END) {
            TaskExecutors.runInUIThread(this::refreshCurrentVersion);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void event(AccountUpdateEvent event) {
        if (accountViewWrapper != null) accountViewWrapper.refreshAccountInfo();
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onDestroyView() {
        accountViewWrapper = null;
        binding = null;
        super.onDestroyView();
    }

    private void runInstallerWithConfirmation(boolean isCustomArgs) {
        if (ProgressKeeper.getTaskCount() == 0)
            Tools.installMod(requireActivity(), isCustomArgs);
        else
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
    }

    @Override
    public void slideIn(AnimPlayer animPlayer) {
        animPlayer.apply(new AnimPlayer.Entry(binding.launcherMenu, Animations.BounceInDown))
                .apply(new AnimPlayer.Entry(binding.playLayout, Animations.BounceInLeft))
                .apply(new AnimPlayer.Entry(binding.playButtonsLayout, Animations.BounceEnlarge));
    }

    @Override
    public void slideOut(AnimPlayer animPlayer) {
        animPlayer.apply(new AnimPlayer.Entry(binding.launcherMenu, Animations.FadeOutUp))
                .apply(new AnimPlayer.Entry(binding.playLayout, Animations.FadeOutRight))
                .apply(new AnimPlayer.Entry(binding.playButtonsLayout, Animations.BounceShrink));
    }
}
