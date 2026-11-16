package com.movtery.zalithlauncher.feature.discord;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.movtery.zalithlauncher.InfoDistributor;
import com.movtery.zalithlauncher.feature.accounts.AccountsManager;
import com.movtery.zalithlauncher.feature.log.Logging;
import com.movtery.zalithlauncher.utils.ZHTools;
import com.movtery.zalithlauncher.utils.path.UrlManager;

import net.kdt.pojavlaunch.value.MinecraftAccount;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Manages Discord account linking and Rich Presence ("Playing Gurkha Launcher",
 * Application ID 1571705435713437726) over the Discord Gateway v10 WebSocket
 * with cross-process persistence between :launcher and :game processes.
 */
public final class DiscordPresenceManager {
    private static final String TAG = "DiscordPresence";
    public static final String DISCORD_APPLICATION_ID = "1571705435713437726";

    private static final String PREFS_NAME = "gurkha_discord_presence";
    private static final String CONFIG_FILE_NAME = "gurkha_discord_rpc.json";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_USER_ID = "linked_user_id";
    private static final String KEY_USERNAME = "linked_username";
    private static final String KEY_TOKEN = "discord_token";
    private static final String KEY_WEBHOOK_URL = "webhook_url";
    private static final String KEY_ONLINE_STATUS = "online_status";
    private static final String KEY_CUSTOM_STATE = "custom_state";

    private static final String GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";
    private static final String USER_ME_URL = "https://discord.com/api/v10/users/@me";
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private static final Pattern SEQ_PATTERN = Pattern.compile("\"s\"\\s*:\\s*(\\d+)");
    private static final Pattern OP_PATTERN = Pattern.compile("\"op\"\\s*:\\s*(\\d+)");
    private static final Pattern TYPE_PATTERN = Pattern.compile("\"t\"\\s*:\\s*\"([^\"]+)\"");

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
    private final File mConfigFile;
    private final OkHttpClient mHttpClient;
    private final ScheduledExecutorService mScheduler;
    private final Handler mMainHandler;
    private final List<StatusListener> mListeners = new CopyOnWriteArrayList<>();

    private volatile WebSocket mWebSocket;
    private volatile ScheduledFuture<?> mHeartbeatFuture;
    private volatile ScheduledFuture<?> mReconnectFuture;
    private volatile Integer mLastSequence = null;
    private volatile boolean mConnected = false;
    private volatile boolean mConnecting = false;
    private volatile String mLastError = "";
    private volatile long mSessionStartTimestamp;
    private volatile String mCurrentDetails = "In " + InfoDistributor.APP_NAME + " Menu";
    private volatile String mCurrentState = "Ready to Play";

    private DiscordPresenceManager(@NonNull Context appContext) {
        mAppContext = appContext;
        mPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mConfigFile = new File(appContext.getFilesDir(), CONFIG_FILE_NAME);
        mHttpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(25, TimeUnit.SECONDS)
                .build();
        mScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GurkhaDiscordRPC");
            t.setDaemon(true);
            return t;
        });
        mMainHandler = new Handler(Looper.getMainLooper());
        mSessionStartTimestamp = System.currentTimeMillis();
        syncFromDiskFile();
    }

    public synchronized void syncFromDiskFile() {
        if (!mConfigFile.isFile()) return;
        try (FileInputStream fis = new FileInputStream(mConfigFile)) {
            byte[] data = new byte[(int) mConfigFile.length()];
            int read = fis.read(data);
            if (read <= 0) return;
            JSONObject obj = new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
            SharedPreferences.Editor editor = mPrefs.edit();
            editor.putBoolean(KEY_ENABLED, obj.optBoolean(KEY_ENABLED, true));
            editor.putString(KEY_USER_ID, obj.optString(KEY_USER_ID, ""));
            editor.putString(KEY_USERNAME, obj.optString(KEY_USERNAME, ""));
            editor.putString(KEY_TOKEN, obj.optString(KEY_TOKEN, ""));
            editor.putString(KEY_WEBHOOK_URL, obj.optString(KEY_WEBHOOK_URL, ""));
            editor.putString(KEY_ONLINE_STATUS, obj.optString(KEY_ONLINE_STATUS, "online"));
            editor.putString(KEY_CUSTOM_STATE, obj.optString(KEY_CUSTOM_STATE, ""));
            editor.commit();
        } catch (Throwable ignored) {
        }
    }

    private synchronized void persistToDiskFile() {
        try {
            JSONObject obj = new JSONObject();
            obj.put(KEY_ENABLED, isEnabled());
            obj.put(KEY_USER_ID, getLinkedUserId());
            obj.put(KEY_USERNAME, getLinkedUsername());
            obj.put(KEY_TOKEN, getToken());
            obj.put(KEY_WEBHOOK_URL, getWebhookUrl());
            obj.put(KEY_ONLINE_STATUS, getOnlineStatus());
            obj.put(KEY_CUSTOM_STATE, getCustomState());
            byte[] bytes = obj.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(mConfigFile, false)) {
                fos.write(bytes);
                fos.getFD().sync();
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean isEnabled() {
        return mPrefs.getBoolean(KEY_ENABLED, true);
    }

    public void setEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_ENABLED, enabled).commit();
        persistToDiskFile();
        if (!enabled) {
            disconnect();
        } else if (isConfigured()) {
            connectIfNeeded();
            broadcastLocalDiscordRpc();
        }
        notifyListeners();
    }

    @NonNull
    public String getLinkedUserId() {
        String id = mPrefs.getString(KEY_USER_ID, "");
        return id != null ? id.trim() : "";
    }

    @NonNull
    public String getLinkedUsername() {
        String name = mPrefs.getString(KEY_USERNAME, "");
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        return getLinkedUserId();
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
    public String getLastError() {
        return mLastError;
    }

    public boolean hasAuthenticatedSession() {
        return !getToken().isEmpty();
    }

    public boolean isConfigured() {
        return !getToken().isEmpty() || !getWebhookUrl().isEmpty()
                || !getLinkedUserId().isEmpty() || !getLinkedUsername().isEmpty();
    }

    public boolean isConnected() {
        if (!isEnabled()) return false;
        if (mConnected) return true;
        return getToken().isEmpty() && !getWebhookUrl().isEmpty();
    }

    public boolean isConnecting() {
        return mConnecting;
    }

    public void saveConfiguration(
            @Nullable String discordIdOrUsername,
            @Nullable String rawToken,
            @Nullable String webhookUrl,
            @Nullable String onlineStatus,
            @Nullable String customState,
            boolean enabled
    ) {
        String cleanIdOrName = discordIdOrUsername != null ? discordIdOrUsername.trim() : "";
        if (cleanIdOrName.startsWith("@")) {
            cleanIdOrName = cleanIdOrName.substring(1).trim();
        }
        String cleanToken = normalizeToken(rawToken);
        // If the user pasted a Discord token into the ID/Login field, detect and use it automatically
        if (cleanToken.isEmpty() && looksLikeDiscordToken(cleanIdOrName)) {
            cleanToken = normalizeToken(cleanIdOrName);
            cleanIdOrName = getLinkedUserId();
        }
        if (cleanToken.isEmpty()) {
            cleanToken = getToken();
        }
        String cleanWebhook = webhookUrl != null ? webhookUrl.trim() : "";
        String cleanStatus = ("idle".equals(onlineStatus) || "dnd".equals(onlineStatus)) ? onlineStatus : "online";
        String cleanCustomState = customState != null ? customState.trim() : "";

        SharedPreferences.Editor editor = mPrefs.edit()
                .putString(KEY_USER_ID, cleanIdOrName)
                .putString(KEY_TOKEN, cleanToken)
                .putString(KEY_WEBHOOK_URL, cleanWebhook)
                .putString(KEY_ONLINE_STATUS, cleanStatus)
                .putString(KEY_CUSTOM_STATE, cleanCustomState)
                .putBoolean(KEY_ENABLED, enabled);
        if (!cleanIdOrName.isEmpty() && !cleanIdOrName.matches("\\d{15,22}")) {
            editor.putString(KEY_USERNAME, cleanIdOrName);
        }
        editor.commit();
        persistToDiskFile();

        if (!cleanToken.isEmpty() && (cleanIdOrName.isEmpty() || getLinkedUsername().isEmpty())) {
            fetchDiscordIdentityAsync(cleanToken);
        }

        disconnect();
        if (enabled && isConfigured()) {
            connectIfNeeded();
            broadcastLocalDiscordRpc();
            postWebhookStatus(mCurrentDetails, resolveEffectiveState());
            notifyListeners();
        } else {
            notifyListeners();
        }
    }

    public void saveConfiguration(
            @Nullable String rawToken,
            @Nullable String webhookUrl,
            @Nullable String onlineStatus,
            @Nullable String customState,
            boolean enabled
    ) {
        saveConfiguration(getLinkedUserId(), rawToken, webhookUrl, onlineStatus, customState, enabled);
    }

    public void onWebSessionAuthenticated(
            @Nullable String userId,
            @Nullable String username,
            @Nullable String sessionToken,
            @Nullable String onlineStatus,
            @Nullable String customState,
            @Nullable String webhookUrl
    ) {
        String cleanId = userId != null ? userId.trim() : "";
        String cleanUser = username != null ? username.trim() : "";
        String cleanToken = normalizeToken(sessionToken);

        mLastError = "";
        SharedPreferences.Editor editor = mPrefs.edit().putBoolean(KEY_ENABLED, true);
        if (!cleanId.isEmpty()) editor.putString(KEY_USER_ID, cleanId);
        if (!cleanUser.isEmpty()) editor.putString(KEY_USERNAME, cleanUser);
        if (!cleanToken.isEmpty()) editor.putString(KEY_TOKEN, cleanToken);
        if (onlineStatus != null && !onlineStatus.isEmpty()) editor.putString(KEY_ONLINE_STATUS, onlineStatus);
        if (customState != null) editor.putString(KEY_CUSTOM_STATE, customState.trim());
        if (webhookUrl != null) editor.putString(KEY_WEBHOOK_URL, webhookUrl.trim());
        editor.commit();
        persistToDiskFile();

        if (!cleanToken.isEmpty()) {
            fetchDiscordIdentityAsync(cleanToken);
        }

        disconnect();
        connectIfNeeded();
        broadcastLocalDiscordRpc();
        postWebhookStatus(mCurrentDetails, resolveEffectiveState());
        notifyListeners();
    }

    public void unlink() {
        mLastError = "";
        mPrefs.edit()
                .remove(KEY_USER_ID)
                .remove(KEY_USERNAME)
                .remove(KEY_TOKEN)
                .remove(KEY_WEBHOOK_URL)
                .putBoolean(KEY_ENABLED, false)
                .commit();
        if (mConfigFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            mConfigFile.delete();
        }
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
        if (!isEnabled() || !isConfigured()) {
            return "Discord";
        }
        String username = getLinkedUsername();
        if (mConnected) {
            return username.isEmpty() ? "Discord: Live" : "Discord: @" + username;
        }
        if (mConnecting) {
            return "Discord: ...";
        }
        if (!getWebhookUrl().isEmpty() && getToken().isEmpty()) {
            return "Discord: Webhook";
        }
        return username.isEmpty() ? "Discord: Sign In" : "Discord: @" + username;
    }

    public void updateActivity(@NonNull String details, @Nullable String state) {
        syncFromDiskFile();
        mCurrentDetails = details;
        if (state != null && !state.trim().isEmpty()) {
            mCurrentState = state.trim();
        }
        if (!isEnabled() || !isConfigured()) {
            return;
        }
        connectIfNeeded();
        sendPresenceUpdate();
        broadcastLocalDiscordRpc();
    }

    public void onGameLaunched(@NonNull String versionName, @Nullable String rendererName) {
        syncFromDiskFile();
        mSessionStartTimestamp = System.currentTimeMillis();
        String details = "Playing Minecraft " + versionName;
        String state = (rendererName != null && !rendererName.isEmpty())
                ? ("Renderer: " + rendererName + " • " + InfoDistributor.APP_NAME)
                : (InfoDistributor.APP_NAME + " " + ZHTools.getVersionName());
        updateActivity(details, state);
        postWebhookStatus(details, resolveEffectiveState());
    }

    public void onLauncherResumed() {
        syncFromDiskFile();
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
        if (mReconnectFuture != null) {
            mReconnectFuture.cancel(false);
            mReconnectFuture = null;
        }
        mConnecting = true;
        notifyListeners();

        Request request = new Request.Builder()
                .url(GATEWAY_URL)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .build();

        mWebSocket = mHttpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                Logging.i(TAG, "Discord Gateway WebSocket opened for app " + DISCORD_APPLICATION_ID);
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                handleGatewayMessage(webSocket, text);
            }

            @Override
            public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                Logging.i(TAG, "Discord Gateway closing: " + code + " " + reason);
                if (code == 4004) {
                    mLastError = "Session expired — please sign in again";
                }
                webSocket.close(1000, null);
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                handleSocketTerminated(code != 4004 && code != 1000);
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                Logging.w(TAG, "Discord Gateway failure: " + t.getMessage());
                handleSocketTerminated(true);
            }
        });
    }

    public synchronized void disconnect() {
        if (mReconnectFuture != null) {
            mReconnectFuture.cancel(false);
            mReconnectFuture = null;
        }
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

    private void handleSocketTerminated(boolean shouldReconnect) {
        synchronized (this) {
            if (mHeartbeatFuture != null) {
                mHeartbeatFuture.cancel(true);
                mHeartbeatFuture = null;
            }
            mWebSocket = null;
            mConnected = false;
            mConnecting = false;
            if (shouldReconnect && isEnabled() && !getToken().isEmpty()) {
                if (mReconnectFuture != null) {
                    mReconnectFuture.cancel(false);
                }
                mReconnectFuture = mScheduler.schedule(this::connectIfNeeded, 5, TimeUnit.SECONDS);
            }
        }
        notifyListeners();
    }

    private void handleGatewayMessage(@NonNull WebSocket webSocket, @NonNull String text) {
        try {
            // Fast path for huge READY / READY_SUPPLEMENTAL payloads (often 1-8 MB on user accounts)
            if (text.length() > 32768) {
                String head = text.substring(0, 1024);
                Matcher sm = SEQ_PATTERN.matcher(head);
                if (sm.find()) {
                    mLastSequence = Integer.parseInt(sm.group(1));
                }
                Matcher tm = TYPE_PATTERN.matcher(head);
                String eventType = tm.find() ? tm.group(1) : "";
                if ("READY".equals(eventType)) {
                    mConnected = true;
                    mConnecting = false;
                    mLastError = "";
                    notifyListeners();
                    sendPresenceUpdate();
                    mScheduler.schedule(this::sendPresenceUpdate, 2, TimeUnit.SECONDS);
                }
                return;
            }

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
                    if ("READY".equals(eventType)) {
                        if (data != null) {
                            JSONObject user = data.optJSONObject("user");
                            if (user != null) {
                                String id = user.optString("id", "");
                                String username = user.optString("username", "");
                                SharedPreferences.Editor editor = mPrefs.edit();
                                if (!id.isEmpty()) editor.putString(KEY_USER_ID, id);
                                if (!username.isEmpty()) editor.putString(KEY_USERNAME, username);
                                editor.commit();
                                persistToDiskFile();
                            }
                        }
                        mConnected = true;
                        mConnecting = false;
                        mLastError = "";
                        notifyListeners();
                        sendPresenceUpdate();
                        mScheduler.schedule(this::sendPresenceUpdate, 2, TimeUnit.SECONDS);
                    }
                    break;
                }
                case 1: { // HEARTBEAT REQUEST
                    sendHeartbeat(webSocket);
                    break;
                }
                case 7: { // RECONNECT
                    disconnect();
                    connectIfNeeded();
                    break;
                }
                case 9: { // INVALID SESSION
                    disconnect();
                    mScheduler.schedule(this::connectIfNeeded, 3, TimeUnit.SECONDS);
                    break;
                }
                default:
                    break;
            }
        } catch (Throwable t) {
            Logging.w(TAG, "Error handling Discord Gateway message: " + t.getMessage());
            // Even if READY JSON parsing hit an edge case, ensure presence update is pushed
            if (text.contains("\"READY\"")) {
                mConnected = true;
                mConnecting = false;
                notifyListeners();
                sendPresenceUpdate();
            }
        }
    }

    private synchronized void startHeartbeat(@NonNull WebSocket webSocket, long intervalMs) {
        if (mHeartbeatFuture != null) {
            mHeartbeatFuture.cancel(true);
        }
        long safeInterval = Math.max(15000L, intervalMs);
        mHeartbeatFuture = mScheduler.scheduleAtFixedRate(
                () -> {
                    sendHeartbeat(webSocket);
                    if (mConnected) {
                        sendPresenceUpdate();
                    }
                },
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
            // Identify with Desktop Client properties (like KizzyRPC) so Discord Gateway accepts
            // type 0 ("Playing") Rich Presence activities with application_id 1571705435713437726.
            JSONObject properties = new JSONObject();
            properties.put("os", "Windows");
            properties.put("browser", "Discord Client");
            properties.put("device", "Discord Client");

            JSONObject data = new JSONObject();
            data.put("token", token);
            data.put("capabilities", 4093);
            data.put("properties", properties);
            data.put("compress", false);
            data.put("presence", buildPresenceObject());

            JSONObject packet = new JSONObject();
            packet.put("op", 2);
            packet.put("d", data);
            boolean sent = webSocket.send(packet.toString());
            if (sent) {
                // Optimistically mark connected after IDENTIFY and push op:3 presence update
                mScheduler.schedule(() -> {
                    if (mWebSocket == webSocket) {
                        mConnected = true;
                        mConnecting = false;
                        notifyListeners();
                        sendPresenceUpdate();
                    }
                }, 1800, TimeUnit.MILLISECONDS);
            }
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
    private JSONObject buildActivityObject() throws Exception {
        JSONObject timestamps = new JSONObject();
        timestamps.put("start", mSessionStartTimestamp);

        JSONObject activity = new JSONObject();
        activity.put("name", InfoDistributor.APP_NAME);
        activity.put("state", resolveEffectiveState());
        activity.put("details", mCurrentDetails);
        activity.put("type", 0); // Playing
        activity.put("created_at", mSessionStartTimestamp);
        activity.put("timestamps", timestamps);
        activity.put("application_id", DISCORD_APPLICATION_ID);
        return activity;
    }

    @NonNull
    private JSONObject buildPresenceObject() throws Exception {
        JSONArray activities = new JSONArray();
        activities.put(buildActivityObject());

        JSONObject presence = new JSONObject();
        presence.put("status", getOnlineStatus());
        presence.put("since", 0);
        presence.put("activities", activities);
        presence.put("afk", false);
        return presence;
    }

    private void broadcastLocalDiscordRpc() {
        if (!isEnabled() || !isConfigured()) return;
        try {
            Intent intent = new Intent("com.discord.intent.action.RPC");
            intent.putExtra("application_id", DISCORD_APPLICATION_ID);
            intent.putExtra("app_id", DISCORD_APPLICATION_ID);
            intent.putExtra("name", InfoDistributor.APP_NAME);
            intent.putExtra("details", mCurrentDetails);
            intent.putExtra("state", resolveEffectiveState());
            intent.putExtra("start_timestamp", mSessionStartTimestamp);
            String userId = getLinkedUserId();
            if (!userId.isEmpty()) {
                intent.putExtra("user_id", userId);
            }
            mAppContext.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    private void fetchDiscordIdentityAsync(@NonNull String token) {
        Request request = new Request.Builder()
                .url(USER_ME_URL)
                .header("Authorization", token)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .get()
                .build();
        mHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    if (response.isSuccessful() && response.body() != null) {
                        JSONObject user = new JSONObject(response.body().string());
                        String id = user.optString("id", "");
                        String username = user.optString("username", "");
                        SharedPreferences.Editor editor = mPrefs.edit();
                        if (!id.isEmpty()) editor.putString(KEY_USER_ID, id);
                        if (!username.isEmpty()) editor.putString(KEY_USERNAME, username);
                        editor.commit();
                        persistToDiskFile();
                        notifyListeners();
                    }
                } catch (Throwable ignored) {
                } finally {
                    response.close();
                }
            }
        });
    }

    public void postWebhookStatus(@NonNull String details, @NonNull String state) {
        String webhook = getWebhookUrl();
        if (!isEnabled() || webhook.isEmpty() || !webhook.startsWith("https://")) {
            return;
        }
        try {
            String mcUser = getActiveMinecraftUsername();
            String discordId = getLinkedUserId();
            String userLine = "";
            if (!discordId.isEmpty()) {
                userLine = discordId.matches("\\d{15,22}")
                        ? ("Discord: <@" + discordId + "> (`" + discordId + "`)\n")
                        : ("Discord: **" + discordId + "**\n");
            }
            JSONObject embed = new JSONObject();
            embed.put("title", "Playing " + InfoDistributor.APP_NAME);
            embed.put("url", UrlManager.URL_DISCORD);
            embed.put("description", userLine + "**" + details + "**\n" + state);
            embed.put("color", 0x5865F2);

            JSONObject footer = new JSONObject();
            footer.put("text", (mcUser.isEmpty() ? "" : ("Player: " + mcUser + " • "))
                    + InfoDistributor.APP_NAME + " • App ID: " + DISCORD_APPLICATION_ID);
            embed.put("footer", footer);

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

    public static boolean looksLikeDiscordToken(@Nullable String raw) {
        if (raw == null) return false;
        String t = normalizeToken(raw);
        if (t.startsWith("mfa.") && t.length() > 30) return true;
        String[] parts = t.split("\\.");
        return parts.length == 3 && t.length() >= 50;
    }

    @NonNull
    public static String normalizeToken(@Nullable String raw) {
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
