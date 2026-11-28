package ro.masadepoker;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Ecranul de start: jocul, Jarvis plutitor (în fundal), Jarvis pe tot ecranul, jocul online. */
public class MainActivity extends Activity {
    static final String ONLINE_URL = "https://poker-server-production-cb58.up.railway.app/";
    private static final int REQ_OVERLAY = 11;
    private static final int INK = Color.parseColor("#0c1512"), PANEL = Color.parseColor("#131f1b"),
            BRASS = Color.parseColor("#dcae55"), CREAM = Color.parseColor("#ece6d6"), MUTED = Color.parseColor("#98aca1");
    private boolean wantJarvis;
    private Button jarvisBtn;

    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(INK);
        getWindow().setNavigationBarColor(INK);
        LocalServer.get(this);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(36), dp(22), dp(28));

        TextView h = new TextView(this);
        h.setText("MASA DE POKER");
        h.setTextColor(CREAM);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        h.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        h.setLetterSpacing(.06f);
        col.addView(h);
        col.addView(text("No-Limit Hold'em cu jetoane de joc, fără bani reali. Jarvis e antrenorul tău.", MUTED, 14, dp(4), dp(22)));

        col.addView(button("Joacă cu boți", true, v -> WebActivity.open(this, LocalServer.get(this).base() + "/")));
        col.addView(text("Cash game sau turneu, 2-6 jucători. Merge fără internet. Jarvis plutitor se leagă singur de joc.", MUTED, 13, dp(6), dp(18)));

        jarvisBtn = button("", true, v -> toggleJarvis());
        col.addView(jarvisBtn);
        col.addView(text("O bulă deasupra oricărei aplicații, care îți arată FOLD / CHECK / CALL / RAISE. Atinge bula ca să deschizi Jarvis, „—” ca să-l strângi. "
                + "Pentru alte mese de antrenament: în Jarvis apasă „Alege fereastra / ecranul” și acceptă captura.", MUTED, 13, dp(6), dp(18)));

        col.addView(button("Jarvis pe tot ecranul", false, v -> WebActivity.open(this, LocalServer.get(this).base() + "/jarvis")));
        col.addView(space(dp(10)));
        col.addView(button("Joc online cu prietenii", false, v -> WebActivity.open(this, ONLINE_URL)));
        col.addView(space(dp(10)));
        col.addView(button("Nu lăsa telefonul să-l oprească (baterie)", false, v -> askBattery()));
        col.addView(text("Pe Samsung: Setări → Baterie → Limite de utilizare în fundal → scoate „Masa de Poker” din aplicațiile adormite.", MUTED, 12, dp(6), dp(18)));

        col.addView(text("Jarvis nu apasă butoane și nu joacă în locul tău. E pentru mese de antrenament, cu jetoane de joc.", MUTED, 12, dp(10), 0));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(INK);
        sv.addView(col);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        jarvisBtn.setText(JarvisService.running ? "Oprește Jarvis plutitor" : "Pornește Jarvis plutitor (în fundal)");
    }

    private void toggleJarvis() {
        if (JarvisService.running) { JarvisService.stop(this); jarvisBtn.postDelayed(this::refresh, 300); return; }
        if (!Settings.canDrawOverlays(this)) {
            wantJarvis = true;
            Toast.makeText(this, "Permite „Afișare peste alte aplicații” pentru Masa de Poker", Toast.LENGTH_LONG).show();
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
            return;
        }
        JarvisService.start(this);
        jarvisBtn.postDelayed(this::refresh, 300);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_OVERLAY && wantJarvis) {
            wantJarvis = false;
            if (Settings.canDrawOverlays(this)) JarvisService.start(this);
            else Toast.makeText(this, "Fără permisiunea de afișare peste alte aplicații, Jarvis nu poate pluti.", Toast.LENGTH_LONG).show();
            jarvisBtn.postDelayed(this::refresh, 300);
        }
    }

    @SuppressLint("BatteryLife")
    private void askBattery() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "Gata: sistemul nu mai oprește aplicația pentru baterie.", Toast.LENGTH_LONG).show();
            return;
        }
        try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); }
        catch (Exception e) { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
    }

    private TextView text(String s, int color, int sp, int top, int bottom) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, top, 0, bottom);
        return t;
    }

    private View space(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
    }

    private Button button(String label, boolean main, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(main ? INK : CREAM);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(54));
        b.setStateListAnimator(null);
        GradientDrawable g = new GradientDrawable();
        g.setColor(main ? BRASS : PANEL);
        g.setCornerRadius(dp(12));
        if (!main) g.setStroke(dp(1), Color.parseColor("#33ece6d6"));
        b.setBackground(g);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return b;
    }
}
