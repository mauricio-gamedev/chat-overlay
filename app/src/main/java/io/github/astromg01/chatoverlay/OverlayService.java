package io.github.astromg01.chatoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private static final String PUSHER_KEY = "32cbd69e4b950bf97679";
    private static final String PUSHER_URL = "wss://ws-us2.pusher.com/app/" + PUSHER_KEY
            + "?protocol=7&client=js&version=8.4.0&flash=false";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<View> messageViews = new ArrayList<>();

    private OkHttpClient http;
    private WebSocket socket;
    private boolean intentionalDisconnect;

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
        startForeground(NOTIFICATION_ID, buildNotification());

        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            intentionalDisconnect = true;
            disconnectSocket();
            removeOverlay();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_TOGGLE_LOCK.equals(action)) {
            toggleLock();
            return START_STICKY;
        }

        if (ACTION_START.equals(action)) {
            String channel = intent.getStringExtra(EXTRA_CHANNEL);
            if (channel != null && !channel.trim().isEmpty()) {
                currentChannel = channel.trim().replace("@", "");
                ensureOverlay();
                connect(currentChannel);
            }
        }

        return START_STICKY;
    }

    private void ensureOverlay() {
        if (overlayAdded) {
            setStatus("Conectando a " + currentChannel + "…", Color.rgb(255, 205, 90));
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlayRoot = new LinearLayout(this);
        overlayRoot.setOrientation(LinearLayout.VERTICAL);
        overlayRoot.setPadding(dp(10), dp(8), dp(10), dp(9));
        overlayRoot.setBackground(roundedBackground(Color.argb(205, 12, 12, 17), dp(14)));
        overlayRoot.setElevation(dp(5));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), 0, dp(2), dp(5));

        TextView title = new TextView(this);
        title.setText("KICK CHAT");
        title.setTextColor(Color.rgb(83, 252, 24));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(11);
        title.setLetterSpacing(0.08f);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        statusView = new TextView(this);
        statusView.setText("CONECTANDO");
        statusView.setTextColor(Color.rgb(255, 205, 90));
        statusView.setTextSize(9);
        header.addView(statusView);

        overlayRoot.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        messageContainer = new LinearLayout(this);
        messageContainer.setOrientation(LinearLayout.VERTICAL);
        overlayRoot.addView(messageContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView waiting = messageText();
        waiting.setText("Aguardando mensagens…");
        waiting.setTextColor(Color.rgb(155, 155, 165));
        waiting.setTag("placeholder");
        messageContainer.addView(waiting);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        windowParams = new WindowManager.LayoutParams(
                dp(330),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        windowParams.gravity = Gravity.TOP | Gravity.START;
        windowParams.x = dp(12);
        windowParams.y = dp(120);

        installDragHandler(header);

        windowManager.addView(overlayRoot, windowParams);
        overlayAdded = true;
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
                    default:
                        return true;
                }
            }
        });
    }

    private void toggleLock() {
        if (!overlayAdded || windowParams == null || windowManager == null) return;
        locked = !locked;

        if (locked) {
            windowParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            windowParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }

        try {
            windowManager.updateViewLayout(overlayRoot, windowParams);
            setStatus(locked ? "FIXADO" : "AO VIVO",
                    locked ? Color.rgb(190, 150, 255) : Color.rgb(83, 252, 24));
        } catch (Exception ignored) {
        }
    }

    private void connect(String channel) {
        intentionalDisconnect = false;
        disconnectSocket();
        setStatus("LOCALIZANDO…", Color.rgb(255, 205, 90));

        io.execute(() -> {
            try {
                long chatroomId = resolveChatroom(channel);
                main.post(() -> {
                    if (!intentionalDisconnect) openWebSocket(chatroomId);
                });
            } catch (Exception e) {
                main.post(() -> {
                    setStatus("ERRO", Color.rgb(255, 95, 95));
                    addSystemMessage("Falha ao localizar o chat. Tentando novamente…");
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
                        .header("User-Agent", "Mozilla/5.0 (Android) ChatOverlay/0.1")
                        .build();

                try (Response response = http.newCall(req).execute()) {
                    if (!response.isSuccessful() || response.body() == null) continue;
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);

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
        socket = http.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handlePusherFrame(webSocket, text, chatroomId);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
                if (intentionalDisconnect) return;
                main.post(() -> {
                    setStatus("RECONECTANDO…", Color.rgb(255, 205, 90));
                    scheduleReconnect();
                });
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (intentionalDisconnect) return;
                main.post(OverlayService.this::scheduleReconnect);
            }
        });
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
                main.post(() -> setStatus(locked ? "FIXADO" : "AO VIVO",
                        locked ? Color.rgb(190, 150, 255) : Color.rgb(83, 252, 24)));
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
            JSONObject data;
            if (dataRaw instanceof JSONObject) {
                data = (JSONObject) dataRaw;
            } else {
                data = new JSONObject(String.valueOf(dataRaw));
            }

            JSONObject sender = data.optJSONObject("sender");
            JSONObject user = data.optJSONObject("user");

            String username = "Usuário";
            String color = null;

            if (sender != null) {
                username = sender.optString("username", sender.optString("slug", username));
                JSONObject identity = sender.optJSONObject("identity");
                if (identity != null) color = identity.optString("color", null);
            } else if (user != null) {
                username = user.optString("username", user.optString("name", username));
                JSONObject identity = user.optJSONObject("identity");
                if (identity != null) color = identity.optString("color", null);
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
                main.post(() -> addChatMessage(finalUsername, finalContent, finalColor));
            }

        } catch (Exception ignored) {
        }
    }

    private void addChatMessage(String username, String message, String colorHex) {
        if (!overlayAdded || messageContainer == null) return;
        removePlaceholder();

        TextView tv = messageText();
        SpannableStringBuilder text = new SpannableStringBuilder();
        int start = text.length();
        text.append(username).append(":");
        int end = text.length();

        int nameColor = Color.rgb(83, 252, 24);
        if (colorHex != null) {
            try { nameColor = Color.parseColor(colorHex); } catch (Exception ignored) {}
        }

        text.setSpan(new ForegroundColorSpan(nameColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append(" ").append(message);

        tv.setText(text);
        messageContainer.addView(tv);
        messageViews.add(tv);

        while (messageViews.size() > 8) {
            View old = messageViews.remove(0);
            messageContainer.removeView(old);
        }

        main.postDelayed(() -> {
            if (messageViews.remove(tv) && messageContainer != null) {
                messageContainer.removeView(tv);
            }
        }, 45000);
    }

    private void addSystemMessage(String message) {
        if (!overlayAdded || messageContainer == null) return;
        TextView tv = messageText();
        tv.setText(message);
        tv.setTextColor(Color.rgb(170, 170, 180));
        messageContainer.addView(tv);
        main.postDelayed(() -> {
            if (messageContainer != null) messageContainer.removeView(tv);
        }, 8000);
    }

    private TextView messageText() {
        TextView tv = new TextView(this);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13);
        tv.setLineSpacing(0, 1.08f);
        tv.setPadding(dp(2), dp(4), dp(2), dp(4));
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
        main.postDelayed(reconnectRunnable, 2500);
    }

    private final Runnable reconnectRunnable = () -> {
        if (!intentionalDisconnect && !currentChannel.isEmpty()) {
            connect(currentChannel);
        }
    };

    private void disconnectSocket() {
        main.removeCallbacks(reconnectRunnable);
        if (socket != null) {
            try { socket.close(1000, "reconnect"); } catch (Exception ignored) {}
            socket = null;
        }
    }

    private void removeOverlay() {
        if (overlayAdded && windowManager != null && overlayRoot != null) {
            try { windowManager.removeView(overlayRoot); } catch (Exception ignored) {}
        }
        overlayAdded = false;
        overlayRoot = null;
        messageContainer = null;
        statusView = null;
        messageViews.clear();
    }

    private GradientDrawable roundedBackground(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), Color.argb(120, 80, 80, 95));
        return drawable;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    "Chat Overlay",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Mantém o chat da Kick conectado enquanto o overlay está ativo.");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this,
                0,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIFICATION_CHANNEL)
                : new Notification.Builder(this);

        return builder
                .setContentTitle("Chat Overlay ativo")
                .setContentText(currentChannel.isEmpty() ? "Kick chat" : "Canal: " + currentChannel)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        intentionalDisconnect = true;
        disconnectSocket();
        removeOverlay();
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
}
