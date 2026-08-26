package io.github.astromg01.chatoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.StyleSpan;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern EMOTE_PATTERN =
            Pattern.compile("\\[emote:(\\d+):([^\\]]+)]");

    private static final int[] FALLBACK_USER_COLORS = new int[]{
            Color.rgb(83, 252, 24),
            Color.rgb(107, 174, 255),
            Color.rgb(196, 132, 255),
            Color.rgb(255, 104, 196),
            Color.rgb(255, 166, 77),
            Color.rgb(64, 224, 208),
            Color.rgb(255, 103, 103),
            Color.rgb(246, 214, 76),
            Color.rgb(91, 214, 156),
            Color.rgb(140, 155, 255)
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService emoteIo = Executors.newFixedThreadPool(2);
    private final Deque<TextView> messageViews = new ArrayDeque<>();

    private final LruCache<String, byte[]> emoteCache = new LruCache<String, byte[]>(6 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return Math.max(1, value.length / 1024);
        }
    };

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
        header.addView(title, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

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
        String safe = URLEncoder.encode(
                channel.toLowerCase(Locale.ROOT),
                StandardCharsets.UTF_8.toString()
        );
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
                        .header("User-Agent", "Mozilla/5.0 (Android) ChatOverlay/0.3")
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
            public void onFailure(
                    WebSocket webSocket,
                    Throwable t,
                    @Nullable Response response
            ) {
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
                            locked
                                    ? Color.rgb(190, 150, 255)
                                    : Color.rgb(83, 252, 24));
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
                username = actor.optString(
                        "username",
                        actor.optString("slug", actor.optString("name", username))
                );

                JSONObject identity = actor.optJSONObject("identity");
                if (identity != null) {
                    color = identity.optString("username_color", null);
                    if (color == null || color.isEmpty()) {
                        color = identity.optString("color", null);
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
                    content = message.optString(
                            "message",
                            message.optString("content", "")
                    );
                }
            }

            if (!content.isEmpty()) {
                String finalUsername = username;
                String finalContent = content;
                String finalColor = color;
                List<BadgeSpec> finalBadges = badges;
                main.post(() -> addChatMessage(
                        finalUsername,
                        finalContent,
                        finalColor,
                        finalBadges
                ));
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
        String normalized = type.trim()
                .toLowerCase(Locale.ROOT)
                .replace(' ', '_')
                .replace('-', '_');

        if (normalized.contains("broadcaster")) return "broadcaster";
        if (normalized.contains("moderator") || normalized.equals("mod")) return "moderator";
        if (normalized.contains("vip")) return "vip";
        if (normalized.contains("subscriber") || normalized.equals("sub")) return "subscriber";
        if (normalized.contains("gifter") || normalized.contains("gift")) return "gifter";
        if (normalized.contains("verified")) return "verified";
        if (normalized.contains("founder")) return "founder";
        if (normalized.equals("og") || normalized.contains("original_gangster")) return "og";
        return normalized;
    }

    private void addChatMessage(
            String username,
            String message,
            String colorHex,
            List<BadgeSpec> badges
    ) {
        if (!overlayAdded || messageContainer == null) return;
        removePlaceholder();

        TextView tv = messageText();
        SpannableStringBuilder text = new SpannableStringBuilder();

        if (badges != null) {
            for (BadgeSpec badge : badges) {
                appendBadge(text, badge);
            }
        }

        int userColor = resolveUserColor(colorHex, username);

        int nameStart = text.length();
        text.append(username).append(":");
        int nameEnd = text.length();
        text.setSpan(
                new ForegroundColorSpan(userColor),
                nameStart,
                nameEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        text.setSpan(
                new StyleSpan(Typeface.BOLD),
                nameStart,
                nameEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        text.append(" ");
        int messageStart = text.length();
        text.append(message);
        int messageEnd = text.length();

        int bodyColor = mixColor(userColor, Color.WHITE, 0.34f);
        text.setSpan(
                new ForegroundColorSpan(bodyColor),
                messageStart,
                messageEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        List<EmoteToken> emotes = findEmotes(message, messageStart);

        tv.setText(text, TextView.BufferType.SPANNABLE);
        messageContainer.addView(tv);
        messageViews.addLast(tv);

        while (messageViews.size() > MAX_MESSAGES) {
            TextView old = messageViews.pollFirst();
            if (old != null && old.getParent() == messageContainer) {
                stopAnimatedSpans(old);
                messageContainer.removeView(old);
            }
        }

        for (EmoteToken emote : emotes) {
            loadEmote(tv, emote);
        }

        main.postDelayed(() -> {
            if (messageViews.remove(tv)
                    && messageContainer != null
                    && tv.getParent() == messageContainer) {
                stopAnimatedSpans(tv);
                messageContainer.removeView(tv);
            }
        }, MESSAGE_LIFETIME_MS);
    }

    private int resolveUserColor(String colorHex, String username) {
        if (colorHex != null && !colorHex.trim().isEmpty()) {
            try {
                return Color.parseColor(colorHex.trim());
            } catch (Exception ignored) {
            }
        }

        String stable = username == null ? "" : username.toLowerCase(Locale.ROOT);
        int index = Math.floorMod(stable.hashCode(), FALLBACK_USER_COLORS.length);
        return FALLBACK_USER_COLORS[index];
    }

    private int mixColor(int base, int target, float targetAmount) {
        float p = Math.max(0f, Math.min(1f, targetAmount));
        float q = 1f - p;
        return Color.rgb(
                Math.round(Color.red(base) * q + Color.red(target) * p),
                Math.round(Color.green(base) * q + Color.green(target) * p),
                Math.round(Color.blue(base) * q + Color.blue(target) * p)
        );
    }

    private List<EmoteToken> findEmotes(String message, int absoluteOffset) {
        List<EmoteToken> result = new ArrayList<>();
        Matcher matcher = EMOTE_PATTERN.matcher(message);
        while (matcher.find()) {
            result.add(new EmoteToken(
                    matcher.group(1),
                    absoluteOffset + matcher.start(),
                    absoluteOffset + matcher.end()
            ));
        }
        return result;
    }

    private void loadEmote(TextView tv, EmoteToken token) {
        byte[] cached = emoteCache.get(token.id);
        if (cached != null) {
            emoteIo.execute(() -> decodeAndApplyEmote(tv, token, cached));
            return;
        }

        emoteIo.execute(() -> {
            byte[] bytes = downloadEmote(token.id);
            if (bytes == null || bytes.length == 0) return;
            emoteCache.put(token.id, bytes);
            decodeAndApplyEmote(tv, token, bytes);
        });
    }

    @Nullable
    private byte[] downloadEmote(String id) {
        Request request = new Request.Builder()
                .url("https://files.kick.com/emotes/" + id + "/fullsize")
                .header("User-Agent", "Mozilla/5.0 (Android) ChatOverlay/0.3")
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            return response.body().bytes();
        } catch (IOException ignored) {
            return null;
        }
    }

    private void decodeAndApplyEmote(TextView tv, EmoteToken token, byte[] bytes) {
        Drawable drawable = decodeEmote(bytes);
        if (drawable == null) return;

        main.post(() -> {
            if (tv.getParent() == null) return;
            CharSequence current = tv.getText();
            if (!(current instanceof Spannable)) return;

            Spannable spannable = (Spannable) current;
            if (token.start < 0 || token.end > spannable.length() || token.start >= token.end) {
                return;
            }

            int height = dp(24);
            int intrinsicW = drawable.getIntrinsicWidth();
            int intrinsicH = drawable.getIntrinsicHeight();
            int width = height;

            if (intrinsicW > 0 && intrinsicH > 0) {
                width = Math.round(height * (intrinsicW / (float) intrinsicH));
                width = Math.max(dp(18), Math.min(width, dp(48)));
            }

            drawable.setBounds(0, 0, width, height);
            drawable.setCallback(tv);

            ImageSpan span = new ImageSpan(drawable, DynamicDrawableSpan.ALIGN_BOTTOM);
            spannable.setSpan(
                    span,
                    token.start,
                    token.end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    && drawable instanceof AnimatedImageDrawable) {
                AnimatedImageDrawable animated = (AnimatedImageDrawable) drawable;
                animated.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                animated.start();
            }

            tv.invalidate();
        });
    }

    @Nullable
    private Drawable decodeEmote(byte[] bytes) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.Source source = ImageDecoder.createSource(ByteBuffer.wrap(bytes));
                return ImageDecoder.decodeDrawable(source, (decoder, info, src) ->
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
            }

            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) return null;
            return new BitmapDrawable(getResources(), bitmap);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void stopAnimatedSpans(TextView tv) {
        CharSequence current = tv.getText();
        if (!(current instanceof Spanned)) return;
        Spanned spanned = (Spanned) current;
        ImageSpan[] spans = spanned.getSpans(0, spanned.length(), ImageSpan.class);
        for (ImageSpan span : spans) {
            Drawable drawable = span.getDrawable();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    && drawable instanceof AnimatedImageDrawable) {
                ((AnimatedImageDrawable) drawable).stop();
            }
            drawable.setCallback(null);
        }
    }

    private void appendBadge(SpannableStringBuilder text, BadgeSpec badge) {
        if (!isSupportedBadge(badge.type)) return;

        int start = text.length();
        text.append('\uFFFC');
        int end = text.length();

        BadgeIconDrawable drawable = new BadgeIconDrawable(badge.type, badgeColor(badge.type));
        int size = dp(16);
        drawable.setBounds(0, 0, size, size);
        text.setSpan(
                new ImageSpan(drawable, DynamicDrawableSpan.ALIGN_BOTTOM),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        );

        if (badge.count > 0 && ("subscriber".equals(badge.type) || "gifter".equals(badge.type))) {
            int countStart = text.length();
            text.append(String.valueOf(badge.count));
            int countEnd = text.length();
            text.setSpan(
                    new ForegroundColorSpan(mixColor(badgeColor(badge.type), Color.WHITE, 0.35f)),
                    countStart,
                    countEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }

        text.append(" ");
    }

    private boolean isSupportedBadge(String type) {
        switch (type) {
            case "broadcaster":
            case "moderator":
            case "vip":
            case "subscriber":
            case "gifter":
            case "verified":
            case "founder":
            case "og":
                return true;
            default:
                return false;
        }
    }

    private int badgeColor(String type) {
        switch (type) {
            case "broadcaster":
                return Color.rgb(190, 92, 255);
            case "moderator":
                return Color.rgb(83, 252, 24);
            case "vip":
                return Color.rgb(255, 72, 194);
            case "subscriber":
                return Color.rgb(93, 221, 122);
            case "gifter":
                return Color.rgb(255, 159, 67);
            case "verified":
                return Color.rgb(73, 165, 255);
            case "founder":
                return Color.rgb(255, 202, 58);
            case "og":
                return Color.rgb(171, 112, 255);
            default:
                return Color.LTGRAY;
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
        long delay = Math.min(
                1500L * (1L << Math.max(0, reconnectAttempt - 1)),
                10_000L
        );
        main.postDelayed(reconnectRunnable, delay);
    }

    private final Runnable reconnectRunnable = () -> {
        if (!intentionalDisconnect
                && !currentChannel.isEmpty()
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
            for (TextView tv : messageViews) {
                stopAnimatedSpans(tv);
            }
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

        Intent toggleIntent = new Intent(this, OverlayService.class)
                .setAction(ACTION_TOGGLE_LOCK);
        PendingIntent togglePending = PendingIntent.getService(
                this,
                1,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, OverlayService.class)
                .setAction(ACTION_STOP);
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
            builder.setForegroundServiceBehavior(
                    Notification.FOREGROUND_SERVICE_IMMEDIATE
            );
        }

        return builder.build();
    }

    private void updateNotification() {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification());
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        intentionalDisconnect = true;
        disconnectSocket();
        removeOverlay();
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        emoteIo.shutdownNow();
        emoteCache.evictAll();
        if (http != null) {
            http.dispatcher().executorService().shutdown();
            http.connectionPool().evictAll();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private final class BadgeIconDrawable extends Drawable {
        private final String type;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        BadgeIconDrawable(String type, int color) {
            this.type = type;
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            stroke.setColor(color);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1.7f));
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float w = b.width();
            float h = b.height();

            switch (type) {
                case "broadcaster":
                    drawMicrophone(canvas, cx, cy, w, h);
                    break;
                case "moderator":
                    drawShield(canvas, cx, cy, w, h);
                    break;
                case "vip":
                    drawStar(canvas, cx, cy, Math.min(w, h) * 0.44f, 5, paint);
                    break;
                case "subscriber":
                    drawDiamond(canvas, cx, cy, w, h);
                    break;
                case "gifter":
                    drawGift(canvas, cx, cy, w, h);
                    break;
                case "verified":
                    drawVerified(canvas, cx, cy, w, h);
                    break;
                case "founder":
                    drawCrown(canvas, cx, cy, w, h);
                    break;
                case "og":
                    drawOg(canvas, cx, cy, w, h);
                    break;
                default:
                    canvas.drawCircle(cx, cy, Math.min(w, h) * 0.22f, paint);
                    break;
            }
        }

        private void drawMicrophone(Canvas canvas, float cx, float cy, float w, float h) {
            float headW = w * 0.30f;
            float headH = h * 0.48f;
            Rect head = new Rect(
                    Math.round(cx - headW / 2f),
                    Math.round(cy - headH / 2f - h * 0.08f),
                    Math.round(cx + headW / 2f),
                    Math.round(cy + headH / 2f - h * 0.08f)
            );
            canvas.drawRoundRect(
                    head.left,
                    head.top,
                    head.right,
                    head.bottom,
                    headW / 2f,
                    headW / 2f,
                    paint
            );
            Path cup = new Path();
            cup.moveTo(cx - w * 0.24f, cy - h * 0.02f);
            cup.quadTo(cx - w * 0.22f, cy + h * 0.28f, cx, cy + h * 0.28f);
            cup.quadTo(cx + w * 0.22f, cy + h * 0.28f, cx + w * 0.24f, cy - h * 0.02f);
            canvas.drawPath(cup, stroke);
            canvas.drawLine(cx, cy + h * 0.28f, cx, cy + h * 0.43f, stroke);
            canvas.drawLine(cx - w * 0.15f, cy + h * 0.43f, cx + w * 0.15f, cy + h * 0.43f, stroke);
        }

        private void drawShield(Canvas canvas, float cx, float cy, float w, float h) {
            Path p = new Path();
            p.moveTo(cx, cy - h * 0.42f);
            p.lineTo(cx + w * 0.34f, cy - h * 0.25f);
            p.lineTo(cx + w * 0.28f, cy + h * 0.16f);
            p.quadTo(cx, cy + h * 0.43f, cx, cy + h * 0.43f);
            p.quadTo(cx - w * 0.28f, cy + h * 0.16f, cx - w * 0.28f, cy + h * 0.16f);
            p.lineTo(cx - w * 0.34f, cy - h * 0.25f);
            p.close();
            canvas.drawPath(p, paint);
        }

        private void drawDiamond(Canvas canvas, float cx, float cy, float w, float h) {
            Path p = new Path();
            p.moveTo(cx, cy - h * 0.40f);
            p.lineTo(cx + w * 0.38f, cy);
            p.lineTo(cx, cy + h * 0.40f);
            p.lineTo(cx - w * 0.38f, cy);
            p.close();
            canvas.drawPath(p, paint);
        }

        private void drawGift(Canvas canvas, float cx, float cy, float w, float h) {
            canvas.drawRect(
                    cx - w * 0.34f,
                    cy - h * 0.08f,
                    cx + w * 0.34f,
                    cy + h * 0.34f,
                    paint
            );
            canvas.drawRect(
                    cx - w * 0.40f,
                    cy - h * 0.20f,
                    cx + w * 0.40f,
                    cy - h * 0.05f,
                    paint
            );
            Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
            cut.setColor(Color.argb(185, 0, 0, 0));
            canvas.drawRect(cx - w * 0.035f, cy - h * 0.20f, cx + w * 0.035f, cy + h * 0.34f, cut);
            canvas.drawCircle(cx - w * 0.11f, cy - h * 0.29f, w * 0.10f, stroke);
            canvas.drawCircle(cx + w * 0.11f, cy - h * 0.29f, w * 0.10f, stroke);
        }

        private void drawVerified(Canvas canvas, float cx, float cy, float w, float h) {
            canvas.drawCircle(cx, cy, Math.min(w, h) * 0.42f, paint);
            Paint check = new Paint(Paint.ANTI_ALIAS_FLAG);
            check.setColor(Color.WHITE);
            check.setStyle(Paint.Style.STROKE);
            check.setStrokeCap(Paint.Cap.ROUND);
            check.setStrokeJoin(Paint.Join.ROUND);
            check.setStrokeWidth(dp(1.8f));
            Path p = new Path();
            p.moveTo(cx - w * 0.20f, cy);
            p.lineTo(cx - w * 0.04f, cy + h * 0.16f);
            p.lineTo(cx + w * 0.23f, cy - h * 0.17f);
            canvas.drawPath(p, check);
        }

        private void drawCrown(Canvas canvas, float cx, float cy, float w, float h) {
            Path p = new Path();
            p.moveTo(cx - w * 0.40f, cy + h * 0.25f);
            p.lineTo(cx - w * 0.34f, cy - h * 0.27f);
            p.lineTo(cx - w * 0.10f, cy - h * 0.05f);
            p.lineTo(cx, cy - h * 0.37f);
            p.lineTo(cx + w * 0.12f, cy - h * 0.05f);
            p.lineTo(cx + w * 0.36f, cy - h * 0.27f);
            p.lineTo(cx + w * 0.40f, cy + h * 0.25f);
            p.close();
            canvas.drawPath(p, paint);
        }

        private void drawOg(Canvas canvas, float cx, float cy, float w, float h) {
            canvas.drawCircle(cx, cy, Math.min(w, h) * 0.40f, stroke);
            canvas.drawCircle(cx, cy, Math.min(w, h) * 0.16f, paint);
        }

        private void drawStar(
                Canvas canvas,
                float cx,
                float cy,
                float outer,
                int points,
                Paint starPaint
        ) {
            float inner = outer * 0.46f;
            Path path = new Path();
            for (int i = 0; i < points * 2; i++) {
                double angle = -Math.PI / 2 + i * Math.PI / points;
                float radius = (i % 2 == 0) ? outer : inner;
                float x = cx + (float) Math.cos(angle) * radius;
                float y = cy + (float) Math.sin(angle) * radius;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            path.close();
            canvas.drawPath(path, starPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            stroke.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            stroke.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static final class EmoteToken {
        final String id;
        final int start;
        final int end;

        EmoteToken(String id, int start, int end) {
            this.id = id;
            this.start = start;
            this.end = end;
        }
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
