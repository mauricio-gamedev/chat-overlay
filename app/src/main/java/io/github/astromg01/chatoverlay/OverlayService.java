package io.github.astromg01.chatoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class OverlayService extends Service {

    public static final String ACTION_START = "io.github.astromg01.chatoverlay.START";
    public static final String ACTION_STOP = "io.github.astromg01.chatoverlay.STOP";
    public static final String ACTION_TOGGLE_LOCK = "io.github.astromg01.chatoverlay.TOGGLE_LOCK";
    public static final String EXTRA_CHANNEL = "channel";

    private static final String NOTIFICATION_CHANNEL = "chat_overlay_service";
    private static final int NOTIFICATION_ID = 1001;
    private static final String PREFS = "chat_overlay";
    private static final String PUSHER_KEY = "32cbd69e4b950bf97679";
    private static final String PUSHER_URL = "wss://ws-us2.pusher.com/app/" + PUSHER_KEY
            + "?protocol=7&client=js&version=8.4.0&flash=false";
    private static final int MAX_MESSAGES = 8;
    private static final long MESSAGE_LIFETIME_MS = 45_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Deque<TextView> messageViews = new ArrayDeque<>();

    private SharedPreferences prefs;
    private OkHttpClient http;
    private WebSocket socket;
    private boolean intentionalDisconnect;
    private int reconnectAttempt;
    private long cachedChatroomId;
    private String cachedChatroomChannel = "";

    private WindowManager windowManager;
    private WindowManager.LayoutParams windowParams;
    private LinearLayout overlayRoot;
    private LinearLayout messageContainer;
    private TextView statusView;
    private boolean overlayAdded;
    private boolean locked;
    private String currentChannel = "";

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        currentChannel = prefs.getString("channel", "");
        locked = prefs.getBoolean("locked", false);
        cachedChatroomId = prefs.getLong("chatroom_id", 0L);
        cachedChatroomChannel = prefs.getString("chatroom_channel", "");

        http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            prefs.edit().putBoolean("service_active", false).apply();
            intentionalDisconnect = true;
            disconnectSocket();
            removeOverlay();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action) && intent != null) {
            String channel = intent.getStringExtra(EXTRA_CHANNEL);
            if (channel != null && !channel.trim().isEmpty()) {
                currentChannel = channel.trim().replace("@", "");
                prefs.edit()
                        .putString("channel", currentChannel)
                        .putBoolean("service_active", true)
                        .apply();
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification());

        boolean shouldBeActive = prefs.getBoolean("service_active", false);
        if (!shouldBeActive || currentChannel.isEmpty()) {
            return START_STICKY;
        }

        if (ACTION_TOGGLE_LOCK.equals(action)) {
            if (!overlayAdded) {
                ensureOverlay();
            }
            toggleLock();
            if (socket == null) {
                connect(currentChannel);
            }
            return START_STICKY;
        }

        if (ACTION_START.equals(action) || intent == null) {
            ensureOverlay();
            if (socket == null) {
                connect(currentChannel);
            }
        }

        return START_STICKY;
    }

    private void ensureOverlay() {
        if (overlayAdded) {
            setStatus("CONECTANDO…", Color.rgb(255, 205, 90));
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return;
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlayRoot = new LinearLayout(this);
        overlayRoot.setOrientation(LinearLayout.VERTICAL);
        overlayRoot.setPadding(dp(8), dp(6), dp(8), dp(7));
        overlayRoot.setBackgroundColor(Color.TRANSPARENT);
        overlayRoot.setElevation(0f);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), 0, dp(2), dp(4));
        header.setBackgroundColor(Color.TRANSPARENT);

        TextView title = new TextView(this);
        title.setText("KICK CHAT");
        title.setTextColor(Color.rgb(83, 252, 24));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(10);
        title.setLetterSpacing(0.08f);
        title.setShadowLayer(4f, 0f, 1f, Color.BLACK);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        statusView = new TextView(this);
        statusView.setText("CONECTANDO");
        statusView.setTextColor(Color.rgb(255, 205, 90));
        statusView.setTextSize(9);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setShadowLayer(4f, 0f, 1f, Color.BLACK);
        header.addView(statusView);

        overlayRoot.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        messageContainer = new LinearLayout(this);
        messageContainer.setOrientation(LinearLayout.VERTICAL);
        messageContainer.setBackgroundColor(Color.TRANSPARENT);
        overlayRoot.addView(messageContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView waiting = messageText();
        waiting.setText("Aguardando mensagens…");
        waiting.setTextColor(Color.rgb(210, 210, 215));
        waiting.setTag("placeholder");
        messageContainer.addView(waiting);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        int windowFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (locked) {
            windowFlags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }

        windowParams = new WindowManager.LayoutParams(
                dp(330),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                windowFlags,
                PixelFormat.TRANSLUCENT
        );
        windowParams.gravity = Gravity.TOP | Gravity.START;
        windowParams.x = prefs.getInt("overlay_x", dp(12));
        windowParams.y = prefs.getInt("overlay_y", dp(120));

        installDragHandler(header);

        windowManager.addView(overlayRoot, windowParams);
        overlayAdded = true;
        setStatus(locked ? "FIXADO" : "CONECTANDO…",
                locked ? Color.rgb(190, 150, 255) : Color.rgb(255, 205, 90));
    }

    private void installDragHandler(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private int startX;
            private int startY;
            private float downX;
            private float downY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (locked || windowParams == null || windowManager == null) return false;

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = windowParams.x;
                        startY = windowParams.y;
                        downX = event.getRawX();
                        downY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        windowParams.x = startX + Math.round(event.getRawX() - downX);
                        windowParams.y = startY + Math.round(event.getRawY() - downY);
                        try {
                            windowManager.updateViewLayout(overlayRoot, windowParams);
                        } catch (Exception ignored) {
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        prefs.edit()
                                .putInt("overlay_x", windowParams.x)
                                .putInt("overlay_y", windowParams.y)
                                .apply();
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void toggleLock() {
        if (!overlayAdded || windowParams == null || windowManager == null) return;
        locked = !locked;
        prefs.edit().putBoolean("locked", locked).apply();

        if (locked) {
            windowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            windowParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }

        try {
            windowManager.updateViewLayout(overlayRoot, windowParams);
            setStatus(locked ? "FIXADO" : "AO VIVO",
                    locked ? Color.rgb(190, 150, 255) : Color.rgb(83, 252, 24));
            updateNotification();
        } catch (Exception ignored) {
        }
    }

    private void connect(String channel) {
        intentionalDisconnect = false;
        disconnectSocket();

        if (cachedChatroomId > 0 && channel.equalsIgnoreCase(cachedChatroomChannel)) {
            openWebSocket(cachedChatroomId);
            return;
        }

        setStatus("LOCALIZANDO…", Color.rgb(255, 205, 90));
        io.execute(() -> {
            try {
                long chatroomId = resolveChatroom(channel);
                cachedChatroomId = chatroomId;
                cachedChatroomChannel = channel;
                prefs.edit()
                        .putLong("chatroom_id", chatroomId)
                        .putString("chatroom_channel", channel)
                        .apply();
                main.post(() -> {
                    if (!intentionalDisconnect) openWebSocket(chatroomId);
                });
            } catch (Exception e) {
                main.post(() -> {
                    setStatus("ERRO", Color.rgb(255, 95, 95));
                    addSystemMessage("Falha ao localizar o chat. Reconectando…");
                    scheduleReconnect();
                });
            }
        });
    }

    private long resolveChatroom(String channel) throws Exception {
        String safe = URLEncoder.encode(channel.toLowerCase(Locale.ROOT), StandardCharsets.UTF_8.toString());
        String[] urls = new String[]{
                "https://kick.com/api/v2/channels/" + safe + "/chatroom",
                "https://kick.com/api/v1/channels/" + safe,
                "https://kick.com/api/v2/channels/" + safe
        };

        Exception last = null;
        for (String url : urls) {
            try {
                Request req = new Request.Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .header("User-Agent", "Mozilla/5.0 (Android) ChatOverlay/0.2")
                        .build();

                try (Response response = http.newCall(req).execute()) {
                    if (!response.isSuccessful() || response.body() == null) continue;
                    JSONObject json = new JSONObject(response.body().string());

                    long id = json.optLong("id", 0);
                    JSONObject chatroom = json.optJSONObject("chatroom");
                    if (chatroom != null) id = chatroom.optLong("id", id);
                    if (id > 0) return id;
                }
            } catch (Exception e) {
                last = e;
            }
        }

        if (last != null) throw last;
        throw new IllegalStateException("Chatroom não encontrado");
    }

    private void openWebSocket(long chatroomId) {
        disconnectSocket();
        setStatus("CONECTANDO…", Color.rgb(255, 205, 90));

        Request request = new Request.Builder().url(PUSHER_URL).build();
        WebSocket newSocket = http.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                if (socket != webSocket) return;
                handlePusherFrame(webSocket, text, chatroomId);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
                if (intentionalDisconnect || socket != webSocket) return;
                socket = null;
                main.post(() -> {
                    setStatus("RECONECTANDO…", Color.rgb(255, 205, 90));
                    scheduleReconnect();
                });
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (intentionalDisconnect || socket != webSocket) return;
                socket = null;
                main.post(OverlayService.this::scheduleReconnect);
            }
        });
        socket = newSocket;
    }

    private void handlePusherFrame(WebSocket webSocket, String raw, long chatroomId) {
        try {
            JSONObject frame = new JSONObject(raw);
            String event = frame.optString("event", "");

            if ("pusher:connection_established".equals(event)) {
                JSONObject data = new JSONObject();
                data.put("auth", "");
                data.put("channel", "chatrooms." + chatroomId + ".v2");

                JSONObject subscribe = new JSONObject();
                subscribe.put("event", "pusher:subscribe");
                subscribe.put("data", data);
                webSocket.send(subscribe.toString());
                return;
            }

            if ("pusher_internal:subscription_succeeded".equals(event)) {
                reconnectAttempt = 0;
                main.post(() -> {
                    setStatus(locked ? "FIXADO" : "AO VIVO",
                            locked ? Color.rgb(190, 150, 255) : Color.rgb(83, 252, 24));
                    updateNotification();
                });
                return;
            }

            if ("pusher:ping".equals(event)) {
                webSocket.send("{\"event\":\"pusher:pong\",\"data\":{}}");
                return;
            }

            if (!"App\\Events\\ChatMessageEvent".equals(event)
                    && !"App\\Events\\ChatMessageSentEvent".equals(event)) {
                return;
            }

            Object dataRaw = frame.opt("data");
            JSONObject data = dataRaw instanceof JSONObject
                    ? (JSONObject) dataRaw
                    : new JSONObject(String.valueOf(dataRaw));

            JSONObject sender = data.optJSONObject("sender");
            JSONObject user = data.optJSONObject("user");
            JSONObject actor = sender != null ? sender : user;

            String username = "Usuário";
            String color = null;
            List<BadgeSpec> badges = new ArrayList<>();

            if (actor != null) {
                username = actor.optString("username",
                        actor.optString("slug", actor.optString("name", username)));

                JSONObject identity = actor.optJSONObject("identity");
                if (identity != null) {
                    color = identity.optString("color", null);
                    if (color == null || color.isEmpty()) {
                        color = identity.optString("username_color", null);
                    }
                    badges = parseBadges(identity, actor);
                } else if (actor.optBoolean("is_verified", false)) {
                    badges.add(new BadgeSpec("verified", 0));
                }
            }

            String content = data.optString("content", "");
            if (content.isEmpty()) {
                JSONObject message = data.optJSONObject("message");
                if (message != null) {
                    content = message.optString("message", message.optString("content", ""));
                }
            }

            if (!content.isEmpty()) {
                String finalUsername = username;
                String finalContent = content;
                String finalColor = color;
                List<BadgeSpec> finalBadges = badges;
                main.post(() -> addChatMessage(finalUsername, finalContent, finalColor, finalBadges));
            }

        } catch (Exception ignored) {
        }
    }

    private List<BadgeSpec> parseBadges(JSONObject identity, JSONObject actor) {
        List<BadgeSpec> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        JSONArray array = identity.optJSONArray("badges");

        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String type = "";
                int count = 0;
                Object raw = array.opt(i);

                if (raw instanceof JSONObject) {
                    JSONObject badge = (JSONObject) raw;
                    type = badge.optString("type", badge.optString("text", ""));
                    count = badge.optInt("count", 0);
                } else if (raw != null) {
                    type = String.valueOf(raw);
                }

                type = normalizeBadgeType(type);
                if (!type.isEmpty() && seen.add(type)) {
                    result.add(new BadgeSpec(type, count));
                }
            }
        }

        if (actor.optBoolean("is_verified", false) && seen.add("verified")) {
            result.add(new BadgeSpec("verified", 0));
        }
        return result;
    }

    private String normalizeBadgeType(String type) {
        if (type == null) return "";
        return type.trim()
                .toLowerCase(Locale.ROOT)
                .replace(' ', '_')
                .replace('-', '_');
    }

    private void addChatMessage(String username, String message, String colorHex, List<BadgeSpec> badges) {
        if (!overlayAdded || messageContainer == null) return;
        removePlaceholder();

        TextView tv = messageText();
        SpannableStringBuilder text = new SpannableStringBuilder();

        if (badges != null) {
            for (BadgeSpec badge : badges) {
                appendBadge(text, badge);
            }
        }

        int nameStart = text.length();
        text.append(username).append(":");
        int nameEnd = text.length();

        int nameColor = Color.rgb(83, 252, 24);
        if (colorHex != null && !colorHex.isEmpty()) {
            try {
                nameColor = Color.parseColor(colorHex);
            } catch (Exception ignored) {
            }
        }

        text.setSpan(new ForegroundColorSpan(nameColor), nameStart, nameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(Typeface.BOLD), nameStart, nameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append(" ").append(message);

        tv.setText(text);
        messageContainer.addView(tv);
        messageViews.addLast(tv);

        while (messageViews.size() > MAX_MESSAGES) {
            TextView old = messageViews.pollFirst();
            if (old != null && old.getParent() == messageContainer) {
                messageContainer.removeView(old);
            }
        }

        main.postDelayed(() -> {
            if (messageViews.remove(tv) && messageContainer != null && tv.getParent() == messageContainer) {
                messageContainer.removeView(tv);
            }
        }, MESSAGE_LIFETIME_MS);
    }

    private void appendBadge(SpannableStringBuilder text, BadgeSpec badge) {
        String label = badgeLabel(badge);
        if (label.isEmpty()) return;

        int start = text.length();
        text.append(label);
        int end = text.length();

        text.setSpan(new ForegroundColorSpan(Color.WHITE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new BackgroundColorSpan(badgeColor(badge.type)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new RelativeSizeSpan(0.72f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append(" ");
    }

    private String badgeLabel(BadgeSpec badge) {
        switch (badge.type) {
            case "broadcaster":
                return " MIC ";
            case "moderator":
                return " MOD ";
            case "vip":
                return " VIP ";
            case "subscriber":
                return badge.count > 0 ? " SUB" + badge.count + " " : " SUB ";
            case "sub_gifter":
            case "gifter":
                return badge.count > 0 ? " GIFT" + badge.count + " " : " GIFT ";
            case "verified":
                return " ✓ ";
            case "founder":
                return " FOUND ";
            case "og":
                return " OG ";
            default:
                if (badge.type.length() <= 8) {
                    return " " + badge.type.toUpperCase(Locale.ROOT) + " ";
                }
                return "";
        }
    }

    private int badgeColor(String type) {
        switch (type) {
            case "broadcaster":
                return Color.rgb(160, 105, 255);
            case "moderator":
                return Color.rgb(83, 252, 24);
            case "vip":
                return Color.rgb(255, 79, 216);
            case "subscriber":
                return Color.rgb(45, 180, 90);
            case "sub_gifter":
            case "gifter":
                return Color.rgb(235, 145, 35);
            case "verified":
                return Color.rgb(35, 145, 235);
            case "founder":
                return Color.rgb(220, 165, 35);
            case "og":
                return Color.rgb(155, 95, 235);
            default:
                return Color.rgb(85, 90, 105);
        }
    }

    private void addSystemMessage(String message) {
        if (!overlayAdded || messageContainer == null) return;
        TextView tv = messageText();
        tv.setText(message);
        tv.setTextColor(Color.rgb(210, 210, 215));
        messageContainer.addView(tv);
        main.postDelayed(() -> {
            if (messageContainer != null && tv.getParent() == messageContainer) {
                messageContainer.removeView(tv);
            }
        }, 8000);
    }

    private TextView messageText() {
        TextView tv = new TextView(this);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13);
        tv.setLineSpacing(0, 1.08f);
        tv.setPadding(dp(2), dp(4), dp(2), dp(4));
        tv.setBackgroundColor(Color.TRANSPARENT);
        tv.setShadowLayer(4f, 0f, 1f, Color.BLACK);
        return tv;
    }

    private void removePlaceholder() {
        if (messageContainer == null) return;
        for (int i = messageContainer.getChildCount() - 1; i >= 0; i--) {
            View v = messageContainer.getChildAt(i);
            if ("placeholder".equals(v.getTag())) {
                messageContainer.removeViewAt(i);
            }
        }
    }

    private void setStatus(String text, int color) {
        if (statusView != null) {
            statusView.setText(text);
            statusView.setTextColor(color);
        }
    }

    private void scheduleReconnect() {
        if (intentionalDisconnect || currentChannel.isEmpty()) return;
        main.removeCallbacks(reconnectRunnable);
        reconnectAttempt = Math.min(reconnectAttempt + 1, 4);
        long delay = Math.min(1500L * (1L << Math.max(0, reconnectAttempt - 1)), 10_000L);
        main.postDelayed(reconnectRunnable, delay);
    }

    private final Runnable reconnectRunnable = () -> {
        if (!intentionalDisconnect && !currentChannel.isEmpty()
                && prefs.getBoolean("service_active", false)) {
            connect(currentChannel);
        }
    };

    private void disconnectSocket() {
        main.removeCallbacks(reconnectRunnable);
        WebSocket old = socket;
        socket = null;
        if (old != null) {
            try {
                old.close(1000, "reconnect");
            } catch (Exception ignored) {
            }
        }
    }

    private void removeOverlay() {
        if (overlayAdded && windowManager != null && overlayRoot != null) {
            try {
                windowManager.removeView(overlayRoot);
            } catch (Exception ignored) {
            }
        }
        overlayAdded = false;
        overlayRoot = null;
        messageContainer = null;
        statusView = null;
        messageViews.clear();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    "Chat Overlay",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Mantém o chat da Kick conectado mesmo com o app fechado.");
            channel.setShowBadge(false);
            channel.enableVibration(false);
            channel.setSound(null, null);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                this,
                0,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent toggleIntent = new Intent(this, OverlayService.class).setAction(ACTION_TOGGLE_LOCK);
        PendingIntent togglePending = PendingIntent.getService(
                this,
                1,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this,
                2,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIFICATION_CHANNEL)
                : new Notification.Builder(this);

        builder
                .setContentTitle("Chat Overlay ativo")
                .setContentText((locked ? "Fixado" : "Ao vivo")
                        + (currentChannel.isEmpty() ? "" : " • " + currentChannel))
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(openPending)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_manage,
                        "Fixar/destravar",
                        togglePending
                ).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Parar",
                        stopPending
                ).build());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }

        return builder.build();
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification());
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // O serviço em primeiro plano continua ativo mesmo se a tela principal do app for fechada.
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        intentionalDisconnect = true;
        disconnectSocket();
        removeOverlay();
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (http != null) {
            http.dispatcher().executorService().shutdown();
            http.connectionPool().evictAll();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class BadgeSpec {
        final String type;
        final int count;

        BadgeSpec(String type, int count) {
            this.type = type;
            this.count = count;
        }
    }
}
