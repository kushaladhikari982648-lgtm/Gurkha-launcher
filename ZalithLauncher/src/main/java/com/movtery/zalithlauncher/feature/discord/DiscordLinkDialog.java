package com.movtery.zalithlauncher.feature.discord;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.movtery.zalithlauncher.InfoDistributor;

/**
 * Dialog that lets the user link their Discord account (via Web Sign-In, Discord Token, or Discord
 * Server Webhook) so others on Discord see "Playing Gurkha Launcher" on their profile and/or channel.
 */
public final class DiscordLinkDialog {
    private DiscordLinkDialog() {
    }

    public static void show(@NonNull Context context) {
        final DiscordPresenceManager manager = DiscordPresenceManager.getInstance(context);
        final float density = context.getResources().getDisplayMetrics().density;
        final int pad = (int) (14 * density);
        final int smallPad = (int) (6 * density);

        ScrollView scrollView = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scrollView.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView statusBanner = new TextView(context);
        String linkedUser = manager.getLinkedUsername();
        String statusLine = manager.isConnected()
                ? ("Connected" + (linkedUser.isEmpty() ? "" : (" as @" + linkedUser))
                   + " • Showing \"Playing " + InfoDistributor.APP_NAME + "\" on Discord")
                : "Link Discord so friends & servers see \"Playing " + InfoDistributor.APP_NAME + "\"";
        statusBanner.setText(statusLine);
        statusBanner.setTextColor(Color.parseColor("#E8EEFF"));
        statusBanner.setBackgroundColor(Color.parseColor("#335865F2"));
        statusBanner.setPadding(pad, smallPad, pad, smallPad);
        statusBanner.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(statusBanner);

        CheckBox enabledCheck = new CheckBox(context);
        enabledCheck.setText("Show \"Playing " + InfoDistributor.APP_NAME + "\" on Discord");
        enabledCheck.setChecked(manager.isEnabled());
        root.addView(enabledCheck);

        Button webLoginBtn = new Button(context);
        webLoginBtn.setAllCaps(false);
        webLoginBtn.setText("Sign in with Discord (Auto-Link)");
        root.addView(webLoginBtn);

        TextView tokenLabel = new TextView(context);
        tokenLabel.setText("Discord RPC / Account Token (filled automatically by Sign-In or paste below):");
        tokenLabel.setPadding(0, smallPad, 0, 0);
        root.addView(tokenLabel);

        final EditText tokenInput = new EditText(context);
        tokenInput.setHint("Discord Token for Live Profile Rich Presence");
        tokenInput.setSingleLine(true);
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        tokenInput.setText(manager.getToken());
        root.addView(tokenInput);

        TextView statusLabel = new TextView(context);
        statusLabel.setText("Discord Online Status & Custom Subtitle:");
        statusLabel.setPadding(0, smallPad, 0, 0);
        root.addView(statusLabel);

        final String[] statusValues = new String[]{"online", "idle", "dnd"};
        final String[] statusLabels = new String[]{"Online", "Idle", "Do Not Disturb"};
        final Spinner statusSpinner = new Spinner(context);
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                statusLabels
        );
        statusSpinner.setAdapter(spinnerAdapter);
        String currentStatus = manager.getOnlineStatus();
        for (int i = 0; i < statusValues.length; i++) {
            if (statusValues[i].equals(currentStatus)) {
                statusSpinner.setSelection(i);
                break;
            }
        }
        root.addView(statusSpinner);

        final EditText customStateInput = new EditText(context);
        customStateInput.setHint("Custom subtitle (optional, e.g. Playing on " + InfoDistributor.APP_NAME + ")");
        customStateInput.setSingleLine(true);
        customStateInput.setText(manager.getCustomState());
        root.addView(customStateInput);

        TextView webhookLabel = new TextView(context);
        webhookLabel.setText("Optional Discord Webhook URL (share game launches to a Discord channel):");
        webhookLabel.setPadding(0, smallPad, 0, 0);
        root.addView(webhookLabel);

        final EditText webhookInput = new EditText(context);
        webhookInput.setHint("https://discord.com/api/webhooks/...");
        webhookInput.setSingleLine(true);
        webhookInput.setText(manager.getWebhookUrl());
        root.addView(webhookInput);

        webLoginBtn.setOnClickListener(v -> showWebLoginDialog(context, token -> {
            tokenInput.setText(token);
            enabledCheck.setChecked(true);
            String chosenStatus = statusValues[Math.max(0, statusSpinner.getSelectedItemPosition())];
            manager.saveConfiguration(
                    token,
                    webhookInput.getText().toString(),
                    chosenStatus,
                    customStateInput.getText().toString(),
                    true
            );
            manager.onLauncherResumed();
            Toast.makeText(
                    context,
                    "Discord linked! Now showing \"Playing " + InfoDistributor.APP_NAME + "\"",
                    Toast.LENGTH_LONG
            ).show();
        }));

        new AlertDialog.Builder(context)
                .setTitle("Discord Link • " + InfoDistributor.APP_NAME)
                .setView(scrollView)
                .setPositiveButton("Save & Link", (dialog, which) -> {
                    String chosenStatus = statusValues[Math.max(0, statusSpinner.getSelectedItemPosition())];
                    manager.saveConfiguration(
                            tokenInput.getText().toString(),
                            webhookInput.getText().toString(),
                            chosenStatus,
                            customStateInput.getText().toString(),
                            enabledCheck.isChecked()
                    );
                    manager.onLauncherResumed();
                    Toast.makeText(
                            context,
                            manager.isConfigured()
                                    ? "Discord status updated: Playing " + InfoDistributor.APP_NAME
                                    : "Discord settings saved",
                            Toast.LENGTH_SHORT
                    ).show();
                })
                .setNeutralButton("Unlink", (dialog, which) -> {
                    manager.unlink();
                    Toast.makeText(context, "Discord unlinked", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private interface TokenCallback {
        void onTokenCaptured(@NonNull String token);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void showWebLoginDialog(@NonNull Context context, @NonNull TokenCallback callback) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        TextView helperText = new TextView(context);
        helperText.setText("Sign in to Discord below. Once logged in, tap \"Link Current Session\".");
        helperText.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (8 * context.getResources().getDisplayMetrics().density);
        helperText.setPadding(pad, pad, pad, pad);
        container.addView(helperText);

        final WebView webView = new WebView(context);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        final AlertDialog[] dialogHolder = new AlertDialog[1];
        final Runnable extractTokenAction = () -> webView.evaluateJavascript(
                "(function(){try{" +
                "var iframe=document.createElement('iframe');document.body.appendChild(iframe);" +
                "var t=iframe.contentWindow.localStorage.token;iframe.remove();" +
                "return t?t.replace(/^\"|\"$/g,''):'';" +
                "}catch(e){return '';}})();",
                value -> {
                    String cleaned = value != null ? value.replace("\"", "").trim() : "";
                    if (!cleaned.isEmpty() && !"null".equalsIgnoreCase(cleaned)) {
                        if (dialogHolder[0] != null && dialogHolder[0].isShowing()) {
                            dialogHolder[0].dismiss();
                        }
                        callback.onTokenCaptured(cleaned);
                    }
                }
        );

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && (url.contains("/channels/") || url.contains("/app"))) {
                    extractTokenAction.run();
                }
            }
        });

        int heightPx = (int) (320 * context.getResources().getDisplayMetrics().density);
        container.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                heightPx
        ));

        AlertDialog webDialog = new AlertDialog.Builder(context)
                .setTitle("Sign in with Discord")
                .setView(container)
                .setPositiveButton("Link Current Session", (d, w) -> extractTokenAction.run())
                .setNegativeButton(android.R.string.cancel, (d, w) -> webView.destroy())
                .create();
        dialogHolder[0] = webDialog;
        webDialog.show();
        webView.loadUrl("https://discord.com/login");
    }
}
