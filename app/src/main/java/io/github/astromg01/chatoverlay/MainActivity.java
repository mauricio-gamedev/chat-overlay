package io.github.astromg01.chatoverlay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_NOTIFICATIONS = 33;

    private EditText channelInput;
    private TextView permissionStatus;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("chat_overlay", MODE_PRIVATE);
        setContentView(buildUi());
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionStatus();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(9, 9, 12));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("Chat Overlay");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setGravity(Gravity.START);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Kick chat em tempo real por cima dos jogos — V0.1");
        subtitle.setTextColor(Color.rgb(170, 170, 180));
        subtitle.setTextSize(14);
        subtitle.setPadding(0, dp(4), 0, dp(22));
        root.addView(subtitle);

        TextView channelLabel = label("Canal da Kick");
        root.addView(channelLabel);

        channelInput = new EditText(this);
        channelInput.setSingleLine(true);
        channelInput.setText(prefs.getString("channel", "MiojoPlays"));
        channelInput.setHint("nome do canal, sem @");
        channelInput.setTextColor(Color.WHITE);
        channelInput.setHintTextColor(Color.rgb(110, 110, 120));
        channelInput.setBackgroundColor(Color.rgb(24, 24, 31));
        channelInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(channelInput, lpMatchWrap(dp(10)));

        permissionStatus = new TextView(this);
        permissionStatus.setTextSize(13);
        permissionStatus.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(permissionStatus, lpMatchWrap(dp(12)));

        Button permissionButton = button("1. Liberar sobreposição");
        permissionButton.setOnClickListener(v -> openOverlayPermission());
        root.addView(permissionButton, lpMatchWrap(dp(10)));

        Button startButton = button("2. Iniciar overlay");
        startButton.setOnClickListener(v -> startOverlay());
        root.addView(startButton, lpMatchWrap(dp(10)));

        Button toggleTouchButton = button("Fixar / destravar toques");
        toggleTouchButton.setOnClickListener(v -> sendServiceAction(OverlayService.ACTION_TOGGLE_LOCK));
        root.addView(toggleTouchButton, lpMatchWrap(dp(10)));

        Button stopButton = button("Parar overlay");
        stopButton.setOnClickListener(v -> sendServiceAction(OverlayService.ACTION_STOP));
        root.addView(stopButton, lpMatchWrap(dp(18)));

        TextView info = new TextView(this);
        info.setText(
                "Como usar:\n" +
                "• Inicie o overlay e arraste pelo topo.\n" +
                "• Quando estiver no lugar certo, fixe os toques.\n" +
                "• Com os toques fixados, os comandos passam direto para o jogo.\n" +
                "• Para destravar, volte neste app e toque no botão de fixar/destravar.\n\n" +
                "A V0.1 mostra até 8 mensagens recentes e reconecta automaticamente se a conexão cair."
        );
        info.setTextColor(Color.rgb(175, 175, 185));
        info.setTextSize(13);
        info.setLineSpacing(0, 1.15f);
        root.addView(info);

        return scroll;
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.rgb(210, 210, 218));
        tv.setTextSize(13);
        tv.setPadding(0, 0, 0, dp(7));
        return tv;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        return b;
    }

    private LinearLayout.LayoutParams lpMatchWrap(int bottomMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = bottomMargin;
        return lp;
    }

    private void refreshPermissionStatus() {
        boolean allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        permissionStatus.setText(allowed
                ? "✓ Permissão 'Aparecer sobre outros apps' liberada"
                : "⚠ Falta liberar 'Aparecer sobre outros apps'");
        permissionStatus.setTextColor(allowed
                ? Color.rgb(120, 255, 96)
                : Color.rgb(255, 190, 90));
        permissionStatus.setBackgroundColor(Color.rgb(20, 20, 27));
    }

    private void openOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        }
    }

    private void startOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Libere a permissão de sobreposição primeiro.", Toast.LENGTH_SHORT).show();
            openOverlayPermission();
            return;
        }

        String channel = channelInput.getText().toString().trim().replace("@", "");
        if (channel.isEmpty()) {
            channelInput.setError("Digite o canal da Kick");
            return;
        }

        prefs.edit().putString("channel", channel).apply();

        Intent intent = new Intent(this, OverlayService.class);
        intent.setAction(OverlayService.ACTION_START);
        intent.putExtra(OverlayService.EXTRA_CHANNEL, channel);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        Toast.makeText(this, "Overlay iniciado para " + channel, Toast.LENGTH_SHORT).show();
    }

    private void sendServiceAction(String action) {
        Intent intent = new Intent(this, OverlayService.class);
        intent.setAction(action);
        try {
            startService(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Inicie o overlay primeiro.", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
