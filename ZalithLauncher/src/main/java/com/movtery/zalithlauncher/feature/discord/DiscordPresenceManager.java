package com.movtery.zalithlauncher.feature.discord;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.movtery.zalithlauncher.InfoDistributor;
import com.movtery.zalithlauncher.feature.accounts.AccountsManager;
import com.movtery.zalithlauncher.feature.log.Logging;
import com.movtery.zalithlauncher.utils.ZHTools;

import net.kdt.pojavlaunch.value.MinecraftAccount;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Manages Discord account linking and Rich Presence ("Playing Gurkha Launcher") over the Discord Gateway
 * WebSocket (v10) as well as optional Discord Webhook status broadcasts.
 */
public final class DiscordPresenceManager {
    private static final String TAG = "DiscordPresence";
    private static final String PREFS_NAME = "gurkha_discord_presence";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_TOKEN = "discord_token";
    private static final String KEY_WEBHOOK_URL = "webhook_url";
    private static final String KEY_ONLINE_STATUS = "online_status";
    private static final String KEY_CUSTOM_STATE = "custom_state";
    private static final String KEY_USERNAME = "linked_username";

    private static final String GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    public interface StatusListener {
        void onDiscordStatusChanged(@NonNull String summary, boolean connected);
    }

    private static volatile DiscordPresenceManager sInstance;

    public static DiscordPresenceManager getInstance(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (DiscordPresenceManager.class) {
                if (sInstance == null) {
                    sInstance = new DiscordPresenceManager(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private final Context mAppContext;
    private final SharedPreferences mPrefs;
    private final OkHttpClient mHttpClient;
    private final ScheduledExecutorService mScheduler;
    private final Handler mMainHandler;
    private final List<StatusListener> mListeners = new CopyOnWriteArrayList<>();

    private volatile WebSocket mWebSocket;
    private volatile ScheduledFuture<?> mHeartbeatFuture;
    private volatile Integer mLastSequence = null;
    private volatile boolean mConnected = false;
    private volatile boolean mConnecting = false;
    private volatile long mSessionStartTimestamp;
    private volatile String mCurrentDetails = "In Launcher Menu";
    private volatile String mCurrentState = "Ready to Play";

    private DiscordPresenceManager(@NonNull Context appContext) {
        mAppContext = appContext;
        mPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mHttpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build();
        mScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GurkhaDiscordRPC");
            t.setDaemon(true);
            return t;
        });
        mMainHandler = new Handler(Looper.getMainLooper());
        mSessionStartTimestamp = System.currentTimeMillis();
    }

    public boolean isEnabled() {
        return mPrefs.getBoolean(KEY_ENABLED, true);
    }

    public void setEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (!enabled) {
            disconnect();
        } else if (isConfigured()) {
            connectIfNeeded();
        }
        notifyListeners();
    }

    @NonNull
    public String getToken() {
        return normalizeToken(mPrefs.getString(KEY_TOKEN, ""));
    }

    @NonNull
    public String getWebhookUrl() {
        String url = mPrefs.getString(KEY_WEBHOOK_URL, "");
        return url != null ? url.trim() : "";
    }

    @NonNull
    public String getOnlineStatus() {
        String status = mPrefs.getString(KEY_ONLINE_STATUS, "online");
        if ("idle".equals(status) || "dnd".equals(status)) {
            return status;
        }
        return "online";
    }

    @NonNull
    public String getCustomState() {
        String custom = mPrefs.getString(KEY_CUSTOM_STATE, "");
        return custom != null ? custom.trim() : "";
    }

    @NonNull
    public String getLinkedUsername() {
        String name = mPrefs.getString(KEY_USERNAME, "");
        return name != null ? name.trim() : "";
    }

    public boolean isConfigured() {
        return !getToken().isEmpty() || !getWebhookUrl().isEmpty();
    }

    public boolean isConnected() {
        return mConnected || (isEnabled() && getToken().isEmpty() && !getWebhookUrl().isEmpty());
    }

    public void saveConfiguration(
            @Nullable String rawToken,
            @Nullable String webhookUrl,
            @Nullable String onlineStatus,
            @Nullable String customState,
            boolean enabled
    ) {
        String cleanToken = normalizeToken(rawToken);
        String cleanWebhook = webhookUrl != null ? webhookUrl.trim() : "";
        String cleanStatus = ("idle".equals(onlineStatus) || "dnd".equals(onlineStatus)) ? onlineStatus : "online";
        String cleanCustomState = customState != null ? customState.trim() : "";

        mPrefs.edit()
                .putString(KEY_TOKEN, cleanToken)
                .putString(KEY_WEBHOOK_URL, cleanWebhook)
                .putString(KEY_ONLINE_STATUS, cleanStatus)
                .putString(KEY_CUSTOM_STATE, cleanCustomState)
                .putBoolean(KEY_ENABLED, enabled)
                .apply();

        disconnect();
        if (enabled && (!cleanToken.isEmpty() || !cleanWebhook.isEmpty())) {
            connectIfNeeded();
            postWebhookStatus(mCurrentDetails, resolveEffectiveState());
        } else {
            notifyListeners();
        }
    }

    public void unlink() {
        mPrefs.edit()
                .remove(KEY_TOKEN)
                .remove(KEY_WEBHOOK_URL)
                .remove(KEY_USERNAME)
                .putBoolean(KEY_ENABLED, false)
                .apply();
        disconnect();
        notifyListeners();
    }

    public void addListener(@NonNull StatusListener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
        listener.onDiscordStatusChanged(getStatusSummary(), isConnected());
    }

    public void removeListener(@NonNull StatusListener listener) {
        mListeners.remove(listener);
    }

    @NonNull
    public String getStatusSummary() {
        if (!isConfigured() || !isEnabled()) {
            return "Discord";
        }
        String username = getLinkedUsername();
        if (mConnected) {
            return username.isEmpty() ? "Discord: On" : "Discord: @" + username;
        }
        if (mConnecting) {
            return "Discord: ...";
        }
        if (!getWebhookUrl().isEmpty() && getToken().isEmpty()) {
            return "Discord: Linked";
        }
        return username.isEmpty() ? "Discord: Ready" : "Discord: @" + username;
    }

    public void updateActivity(@NonNull String details, @Nullable String state) {
        mCurrentDetails = details;
        if (state != null && !state.trim().isEmpty()) {
            mCurrentState = state.trim();
        }
        if (!isEnabled() || !isConfigured()) {
            return;
        }
        connectIfNeeded();
        sendPresenceUpdate();
    }

    public void onGameLaunched(@NonNull String versionName, @Nullable String rendererName) {
        mSessionStartTimestamp = System.currentTimeMillis();
        String details = "Playing Minecraft " + versionName;
        String state = (rendererName != null && !rendererName.isEmpty())
                ? ("Renderer: " + rendererName + " • " + InfoDistributor.APP_NAME)
                : (InfoDistributor.APP_NAME + " " + ZHTools.getVersionName());
        updateActivity(details, state);
        postWebhookStatus(details, resolveEffectiveState());
    }

    public void onLauncherResumed() {
        String accountName = getActiveMinecraftUsername();
        String state = accountName.isEmpty()
                ? (InfoDistributor.APP_NAME + " " + ZHTools.getVersionName())
                : ("Player: " + accountName);
        updateActivity("In " + InfoDistributor.APP_NAME + " Menu", state);
    }

    public synchronized void connectIfNeeded() {
        if (!isEnabled()) return;
        String token = getToken();
        if (token.isEmpty()) {
            notifyListeners();
            return;
        }
        if (mConnected || mConnecting || mWebSocket != null) {
            return;
        }
        mConnecting = true;
        notifyListeners();

        Request request = new Request.Builder()
                .url(GATEWAY_URL)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36")
                .build();

        mWebSocket = mHttpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                Logging.i(TAG, "Discord Gateway WebSocket opened");
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                handleGatewayMessage(webSocket, text);
            }

            @Override
            public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                Logging.i(TAG, "Discord Gateway closing: " + code + " " + reason);
                webSocket.close(1000, null);
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                handleSocketTerminated();
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                Logging.w(TAG, "Discord Gateway failure: " + t.getMessage());
                handleSocketTerminated();
            }
        });
    }

    public synchronized void disconnect() {
        if (mHeartbeatFuture != null) {
            mHeartbeatFuture.cancel(true);
            mHeartbeatFuture = null;
        }
        if (mWebSocket != null) {
            try {
                mWebSocket.close(1000, "User disconnected");
            } catch (Throwable ignored) {
            }
            mWebSocket = null;
        }
        mConnected = false;
        mConnecting = false;
        mLastSequence = null;
    }

    private void handleSocketTerminated() {
        synchronized (this) {
            if (mHeartbeatFuture != null) {
                mHeartbeatFuture.cancel(true);
                mHeartbeatFuture = null;
            }
            mWebSocket = null;
            mConnected = false;
            mConnecting = false;
        }
        notifyListeners();
    }

    private void handleGatewayMessage(@NonNull WebSocket webSocket, @NonNull String text) {
        try {
            JSONObject packet = new JSONObject(text);
            if (!packet.isNull("s")) {
                mLastSequence = packet.optInt("s");
            }
            int op = packet.optInt("op", -1);
            String eventType = packet.optString("t", "");
            JSONObject data = packet.optJSONObject("d");

            switch (op) {
                case 10: { // HELLO
                    long interval = data != null ? data.optLong("heartbeat_interval", 41250L) : 41250L;
                    startHeartbeat(webSocket, interval);
                    sendIdentify(webSocket);
                    break;
                }
                case 0: { // DISPATCH
                    if ("READY".equals(eventType) && data != null) {
                        JSONObject user = data.optJSONObject("user");
                        if (user != null) {
                            String username = user.optString("username", "");
                            if (!username.isEmpty()) {
                                mPrefs.edit().putString(KEY_USERNAME, username).apply();
                            }
                        }
                        mConnected = true;
                        mConnecting = false;
                        notifyListeners();
                        sendPresenceUpdate();
                    }
                    break;
                }
                case 1: { // HEARTBEAT REQUEST
                    sendHeartbeat(webSocket);
                    break;
                }
                case 7: // RECONNECT
                case 9: { // INVALID SESSION
                    disconnect();
                    break;
                }
                default:
                    break;
            }
        } catch (Throwable t) {
            Logging.w(TAG, "Error handling Discord Gateway message: " + t.getMessage());
        }
    }

    private synchronized void startHeartbeat(@NonNull WebSocket webSocket, long intervalMs) {
        if (mHeartbeatFuture != null) {
            mHeartbeatFuture.cancel(true);
        }
        long safeInterval = Math.max(15000L, intervalMs);
        mHeartbeatFuture = mScheduler.scheduleAtFixedRate(
                () -> sendHeartbeat(webSocket),
                safeInterval,
                safeInterval,
                TimeUnit.MILLISECONDS
        );
    }

    private void sendHeartbeat(@NonNull WebSocket webSocket) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("op", 1);
            Integer seq = mLastSequence;
            payload.put("d", seq != null ? seq : JSONObject.NULL);
            webSocket.send(payload.toString());
        } catch (Throwable ignored) {
        }
    }

    private void sendIdentify(@NonNull WebSocket webSocket) {
        String token = getToken();
        if (token.isEmpty()) return;
        try {
            JSONObject properties = new JSONObject();
            properties.put("os", "Android");
            properties.put("browser", "Discord Android");
            properties.put("device", InfoDistributor.APP_NAME);

            JSONObject data = new JSONObject();
            data.put("token", token);
            data.put("capabilities", 65);
            data.put("properties", properties);
            data.put("presence", buildPresenceObject());

            JSONObject packet = new JSONObject();
            packet.put("op", 2);
            packet.put("d", data);
            webSocket.send(packet.toString());
        } catch (Throwable t) {
            Logging.w(TAG, "Failed to send Discord IDENTIFY: " + t.getMessage());
        }
    }

    public void sendPresenceUpdate() {
        WebSocket ws = mWebSocket;
        if (ws == null) return;
        try {
            JSONObject packet = new JSONObject();
            packet.put("op", 3);
            packet.put("d", buildPresenceObject());
            ws.send(packet.toString());
        } catch (Throwable t) {
            Logging.w(TAG, "Failed to send Discord presence update: " + t.getMessage());
        }
    }

    @NonNull
    private JSONObject buildPresenceObject() throws Exception {
        JSONObject timestamps = new JSONObject();
        timestamps.put("start", mSessionStartTimestamp);

        JSONObject activity = new JSONObject();
        activity.put("name", InfoDistributor.APP_NAME);
        activity.put("type", 0); // Playing
        activity.put("details", mCurrentDetails);
        activity.put("state", resolveEffectiveState());
        activity.put("timestamps", timestamps);

        JSONArray activities = new JSONArray();
        activities.put(activity);

        JSONObject presence = new JSONObject();
        presence.put("status", getOnlineStatus());
        presence.put("since", 0);
        presence.put("activities", activities);
        presence.put("afk", false);
        return presence;
    }

    public void postWebhookStatus(@NonNull String details, @NonNull String state) {
        String webhook = getWebhookUrl();
        if (!isEnabled() || webhook.isEmpty() || !webhook.startsWith("https://")) {
            return;
        }
        try {
            String mcUser = getActiveMinecraftUsername();
            JSONObject embed = new JSONObject();
            embed.put("title", "Playing " + InfoDistributor.APP_NAME);
            embed.put("description", "**" + details + "**\n" + state);
            embed.put("color", 0x5865F2);
            if (!mcUser.isEmpty()) {
                JSONObject footer = new JSONObject();
                footer.put("text", "Player: " + mcUser + " • " + InfoDistributor.APP_NAME + " " + ZHTools.getVersionName());
                embed.put("footer", footer);
            }

            JSONArray embeds = new JSONArray();
            embeds.put(embed);

            JSONObject body = new JSONObject();
            body.put("username", InfoDistributor.APP_NAME);
            body.put("embeds", embeds);

            Request request = new Request.Builder()
                    .url(webhook)
                    .post(RequestBody.create(body.toString(), JSON_MEDIA))
                    .build();
            mHttpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Logging.w(TAG, "Discord webhook status failed: " + e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    response.close();
                }
            });
        } catch (Throwable ignored) {
        }
    }

    @NonNull
    private String resolveEffectiveState() {
        String custom = getCustomState();
        if (!custom.isEmpty()) {
            return custom;
        }
        return mCurrentState;
    }

    @NonNull
    private static String normalizeToken(@Nullable String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1).trim();
        }
        if (t.regionMatches(true, 0, "Bearer ", 0, 7)) {
            t = t.substring(7).trim();
        }
        return t;
    }

    @NonNull
    private static String getActiveMinecraftUsername() {
        try {
            MinecraftAccount account = AccountsManager.INSTANCE.getCurrentAccount();
            if (account != null && account.username != null) {
                return account.username;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private void notifyListeners() {
        final String summary = getStatusSummary();
        final boolean connected = isConnected();
        mMainHandler.post(() -> {
            for (StatusListener listener : mListeners) {
                listener.onDiscordStatusChanged(summary, connected);
            }
        });
    }
}
