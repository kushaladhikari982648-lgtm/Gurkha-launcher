package com.movtery.zalithlauncher.feature.discord;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.movtery.zalithlauncher.InfoDistributor;
import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.utils.ZHTools;
import com.movtery.zalithlauncher.utils.path.UrlManager;

/**
 * Liquid-Glass Discord Linking Dialog styled identically to the Gurkha Launcher UI.
 * Connects via Discord Application ID 1571705435713437726 using the user's Discord ID/Username
 * or one-step Discord Account Sign-In without ever requiring the user to manually paste a token.
 */
public final class DiscordLinkDialog {
    public static final String DISCORD_INVITE_URL = UrlManager.URL_DISCORD;
    public static final String DISCORD_APPLICATION_ID = DiscordPresenceManager.DISCORD_APPLICATION_ID;

    private DiscordLinkDialog() {
    }

    public static void show(@NonNull Context context) {
        final DiscordPresenceManager manager = DiscordPresenceManager.getInstance(context);
        final View view = LayoutInflater.from(context).inflate(R.layout.dialog_discord_link, null, false);

        final TextView statusBadge = view.findViewById(R.id.discord_status_badge);
        final Switch enableSwitch = view.findViewById(R.id.discord_enable_switch);
        final EditText discordIdInput = view.findViewById(R.id.discord_id_input);
        final Button webLoginBtn = view.findViewById(R.id.discord_web_login_button);
        final Button statusOnlineBtn = view.findViewById(R.id.status_online_btn);
        final Button statusIdleBtn = view.findViewById(R.id.status_idle_btn);
        final Button statusDndBtn = view.findViewById(R.id.status_dnd_btn);
        final EditText customStateInput = view.findViewById(R.id.discord_custom_state_input);
        final EditText webhookInput = view.findViewById(R.id.discord_webhook_input);
        final Button joinServerBtn = view.findViewById(R.id.discord_join_server_button);
        final Button unlinkBtn = view.findViewById(R.id.discord_unlink_button);
        final Button cancelBtn = view.findViewById(R.id.discord_cancel_button);
        final Button saveBtn = view.findViewById(R.id.discord_save_button);

        enableSwitch.setChecked(manager.isEnabled());
        String existingUser = manager.getLinkedUsername();
        String existingId = manager.getLinkedUserId();
        discordIdInput.setText(!existingId.isEmpty() ? existingId : existingUser);
        customStateInput.setText(manager.getCustomState());
        webhookInput.setText(manager.getWebhookUrl());

        final String[] selectedStatus = new String[]{manager.getOnlineStatus()};
        final Runnable updateStatusPills = () -> {
            statusOnlineBtn.setSelected("online".equals(selectedStatus[0]));
            statusIdleBtn.setSelected("idle".equals(selectedStatus[0]));
            statusDndBtn.setSelected("dnd".equals(selectedStatus[0]));
            statusOnlineBtn.setAlpha("online".equals(selectedStatus[0]) ? 1.0f : 0.65f);
            statusIdleBtn.setAlpha("idle".equals(selectedStatus[0]) ? 1.0f : 0.65f);
            statusDndBtn.setAlpha("dnd".equals(selectedStatus[0]) ? 1.0f : 0.65f);
        };
        updateStatusPills.run();

        statusOnlineBtn.setOnClickListener(v -> {
            selectedStatus[0] = "online";
            updateStatusPills.run();
        });
        statusIdleBtn.setOnClickListener(v -> {
            selectedStatus[0] = "idle";
            updateStatusPills.run();
        });
        statusDndBtn.setOnClickListener(v -> {
            selectedStatus[0] = "dnd";
            updateStatusPills.run();
        });

        final Runnable refreshBanner = () -> {
            String user = manager.getLinkedUsername();
            if (manager.isConnected()) {
                statusBadge.setText("Connected" + (user.isEmpty() ? "" : (" as @" + user))
                        + " • Playing " + InfoDistributor.APP_NAME + " (App ID: " + DISCORD_APPLICATION_ID + ")");
                statusBadge.setTextColor(Color.parseColor("#A8F0C6"));
            } else {
                statusBadge.setText("Enter your Discord ID or Sign In below so others on Discord see \"Playing "
                        + InfoDistributor.APP_NAME + "\"");
                statusBadge.setTextColor(Color.parseColor("#B8D4C9"));
            }
        };
        refreshBanner.run();

        joinServerBtn.setOnClickListener(v -> ZHTools.openLink(context, DISCORD_INVITE_URL));

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(view)
                .create();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        webLoginBtn.setOnClickListener(v -> showWebLoginDialog(
                context,
                (userId, username, token) -> {
                    String displayId = (username != null && !username.isEmpty()) ? username : userId;
                    if (displayId != null && !displayId.isEmpty()) {
                        discordIdInput.setText(displayId);
                    }
                    enableSwitch.setChecked(true);
                    manager.onWebSessionAuthenticated(
                            userId,
                            username,
                            token,
                            selectedStatus[0],
                            customStateInput.getText().toString(),
                            webhookInput.getText().toString()
                    );
                    refreshBanner.run();
                    Toast.makeText(
                            context,
                            "Connected to Discord" + (displayId != null && !displayId.isEmpty() ? (" as @" + displayId) : "")
                                    + " • Playing " + InfoDistributor.APP_NAME,
                            Toast.LENGTH_SHORT
                    ).show();
                }
        ));

        unlinkBtn.setOnClickListener(v -> {
            manager.unlink();
            Toast.makeText(context, "Discord unlinked", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        saveBtn.setOnClickListener(v -> {
            String enteredId = discordIdInput.getText().toString().trim();
            String enteredWebhook = webhookInput.getText().toString().trim();
            if (enteredId.isEmpty() && manager.getToken().isEmpty() && enteredWebhook.isEmpty()) {
                Toast.makeText(
                        context,
                        "Enter your Discord ID / Username or tap Sign in with Discord",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }
            manager.saveConfiguration(
                    enteredId,
                    manager.getToken(),
                    enteredWebhook,
                    selectedStatus[0],
                    customStateInput.getText().toString(),
                    enableSwitch.isChecked()
            );
            Toast.makeText(
                    context,
                    enableSwitch.isChecked()
                            ? ("Discord connected! Showing Playing " + InfoDistributor.APP_NAME)
                            : "Discord settings saved",
                    Toast.LENGTH_SHORT
            ).show();
            dialog.dismiss();
        });

        dialog.show();
    }

    private interface WebAuthCallback {
        void onAuthenticated(String userId, String username, String sessionToken);
    }

    private static final String JS_INSTALL_INTERCEPTOR =
            "(function(){"
            + "if(window.__gurkhaHooked)return;"
            + "window.__gurkhaHooked=true;"
            + "window.__gurkhaToken='';"
            + "try{"
            + "var origSet=XMLHttpRequest.prototype.setRequestHeader;"
            + "XMLHttpRequest.prototype.setRequestHeader=function(k,v){"
            + "if(k&&k.toLowerCase()==='authorization'&&v){window.__gurkhaToken=v;}"
            + "return origSet.apply(this,arguments);"
            + "};"
            + "}catch(e){}"
            + "})();";

    private static final String JS_POLL_AUTH =
            "(function(){"
            + "try{"
            + "var t=window.__gurkhaToken||'';"
            + "if(!t){"
            + "var f=document.createElement('iframe');"
            + "f.style.display='none';"
            + "document.body.appendChild(f);"
            + "var s=f.contentWindow.localStorage.getItem('token');"
            + "f.remove();"
            + "if(s)t=s.replace(/^\"|\"$/g,'');"
            + "}"
            + "if(!t&&window.webpackChunkdiscord_app){"
            + "window.webpackChunkdiscord_app.push([[Math.random()],{},function(req){"
            + "for(var k in req.c){"
            + "var m=req.c[k];"
            + "if(m&&m.exports&&m.exports.default&&typeof m.exports.default.getToken==='function'){"
            + "var tk=m.exports.default.getToken();"
            + "if(tk)t=tk;"
            + "}"
            + "}"
            + "}]);"
            + "}"
            + "return t||'';"
            + "}catch(e){return '';}"
            + "})();";

    @SuppressLint("SetJavaScriptEnabled")
    private static void showWebLoginDialog(@NonNull Context context, @NonNull WebAuthCallback callback) {
        final float density = context.getResources().getDisplayMetrics().density;
        final int pad = (int) (10 * density);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundResource(R.drawable.mc_panel);
        container.setPadding(pad, pad, pad, pad);

        TextView header = new TextView(context);
        header.setText("Sign in with Discord • App ID: " + DISCORD_APPLICATION_ID + "\nLog in with your Discord account; it will connect automatically.");
        header.setTextColor(Color.parseColor("#F0F3EE"));
        header.setTextSize(12f);
        header.setPadding(pad, pad / 2, pad, pad);
        container.addView(header);

        final WebView webView = new WebView(context);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        webView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (360 * density)
        ));
        container.addView(webView);

        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setPadding(0, pad, 0, 0);

        Button cancelBtn = new Button(context);
        cancelBtn.setBackgroundResource(R.drawable.mc_button);
        cancelBtn.setTextColor(Color.parseColor("#F0F3EE"));
        cancelBtn.setAllCaps(false);
        cancelBtn.setText(android.R.string.cancel);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                0,
                (int) (38 * density),
                1f
        );
        cancelLp.setMarginEnd(pad / 2);
        buttonRow.addView(cancelBtn, cancelLp);

        Button connectNowBtn = new Button(context);
        connectNowBtn.setBackgroundResource(R.drawable.mc_play);
        connectNowBtn.setTextColor(Color.parseColor("#18301B"));
        connectNowBtn.setAllCaps(false);
        connectNowBtn.setText("Connect Current Session");
        LinearLayout.LayoutParams connectLp = new LinearLayout.LayoutParams(
                0,
                (int) (38 * density),
                1.4f
        );
        buttonRow.addView(connectNowBtn, connectLp);
        container.addView(buttonRow);

        final AlertDialog loginDialog = new AlertDialog.Builder(context)
                .setView(container)
                .create();
        Window window = loginDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        final boolean[] completed = new boolean[]{false};
        final Runnable pollTokenRunnable = new Runnable() {
            @Override
            public void run() {
                if (completed[0] || !loginDialog.isShowing()) return;
                webView.evaluateJavascript(JS_INSTALL_INTERCEPTOR, null);
                webView.evaluateJavascript(JS_POLL_AUTH, value -> {
                    if (completed[0] || !loginDialog.isShowing()) return;
                    String token = cleanJsString(value);
                    if (!token.isEmpty() && token.length() > 16 && !"null".equalsIgnoreCase(token) && !"undefined".equalsIgnoreCase(token)) {
                        completed[0] = true;
                        callback.onAuthenticated("", "", token);
                        loginDialog.dismiss();
                    } else {
                        webView.postDelayed(this, 900L);
                    }
                });
            }
        };

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                view.evaluateJavascript(JS_INSTALL_INTERCEPTOR, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.evaluateJavascript(JS_INSTALL_INTERCEPTOR, null);
                if (url != null && url.contains("access_token=")) {
                    int idx = url.indexOf("access_token=");
                    String sub = url.substring(idx + "access_token=".length());
                    int amp = sub.indexOf('&');
                    String accessToken = amp >= 0 ? sub.substring(0, amp) : sub;
                    if (!accessToken.isEmpty() && !completed[0]) {
                        completed[0] = true;
                        callback.onAuthenticated("", "", accessToken);
                        loginDialog.dismiss();
                    }
                }
            }
        });

        cancelBtn.setOnClickListener(v -> loginDialog.dismiss());
        connectNowBtn.setOnClickListener(v -> webView.evaluateJavascript(JS_POLL_AUTH, value -> {
            String token = cleanJsString(value);
            if (!token.isEmpty() && !"null".equalsIgnoreCase(token) && !"undefined".equalsIgnoreCase(token)) {
                completed[0] = true;
                callback.onAuthenticated("", "", token);
                loginDialog.dismiss();
            } else {
                Toast.makeText(
                        context,
                        "Finish signing in on the Discord page above, or close and enter your Discord ID directly",
                        Toast.LENGTH_LONG
                ).show();
            }
        }));

        loginDialog.setOnDismissListener(d -> {
            completed[0] = true;
            webView.destroy();
        });

        loginDialog.show();
        webView.loadUrl("https://discord.com/login");
        webView.postDelayed(pollTokenRunnable, 1000L);
    }

    @NonNull
    private static String cleanJsString(String value) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.startsWith("\"") && clean.endsWith("\"") && clean.length() >= 2) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }
        clean = clean.replace("\\\"", "\"").trim();
        if (clean.startsWith("\"") && clean.endsWith("\"") && clean.length() >= 2) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }
        return clean;
    }
}
