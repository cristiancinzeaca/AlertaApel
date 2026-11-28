package ro.masadepoker;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Jarvis în fundal: o fereastră plutitoare peste orice aplicație.
 *  - strânsă, e o bulă mică ce arată recomandarea (FOLD / CHECK / CALL / RAISE);
 *  - deschisă, e pagina completă a lui Jarvis.
 * Pagina rulează mereu (și când e strânsă), iar serviciul ține aplicația vie cu o notificare.
 * Pentru „Alege fereastra / ecranul” serviciul capturează ecranul (MediaProjection) și dă imaginile paginii prin /frame.
 */
public class JarvisService extends Service {
    public static final String ACTION_STOP = "ro.masadepoker.STOP";
    public static final String ACTION_PROJECTION = "ro.masadepoker.PROJECTION";
    public static final String ACTION_SHOW = "ro.masadepoker.SHOW";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    private static final String CHANNEL = "jarvis";
    private static final int NOTIF_ID = 1;
    private static final long FRAME_MS = 400;
    private static final int MAX_CAPTURE_W = 1080;

    public static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LocalServer server;
    private FrameLayout root;
    private LinearLayout panel;
    private TextView bubble;
    private WebView web;
    private WindowManager.LayoutParams lp;
    private boolean expanded = true;
    private int panelW, panelH, bubbleW, bubbleH;
    private int savedX, savedY;
    private String lastType = "";

    // captura
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread capThread;
    private Handler capHandler;
    private long lastFrameAt;
    private Bitmap scratch;
    private int capW, capH;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        wm = getSystemService(WindowManager.class);
        server = LocalServer.get(this);
        createChannel();
        goForeground(false);
        buildOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(a)) { stopSelf(); return START_NOT_STICKY; }
        if (ACTION_PROJECTION.equals(a)) {
            int code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            if (code == Activity.RESULT_OK && data != null) startProjection(code, data);
            else { goForeground(projection != null); js("window.__jvCaptureFailed&&__jvCaptureFailed('Ai refuzat captura ecranului.')"); }
            return START_NOT_STICKY;
        }
        goForeground(projection != null);
        if (ACTION_SHOW.equals(a)) setExpanded(true);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        stopProjection(false);
        if (web != null) { try { wm.removeView(root); } catch (Exception ignored) { } web.destroy(); web = null; }
        super.onDestroy();
    }

    // ---------- notificarea ----------

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Jarvis în fundal", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Ține fereastra lui Jarvis deschisă peste alte aplicații");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification notification(boolean capturing) {
        int pf = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent show = PendingIntent.getService(this, 1, new Intent(this, JarvisService.class).setAction(ACTION_SHOW), pf);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, JarvisService.class).setAction(ACTION_STOP), pf);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Jarvis rulează în fundal")
                .setContentText(capturing ? "Citește masa de pe ecran" : "Atinge ca să deschizi fereastra")
                .setContentIntent(show)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Oprește Jarvis", stop).build())
                .build();
    }

    private void goForeground(boolean capturing) {
        Notification n = notification(capturing);
        if (Build.VERSION.SDK_INT >= 34) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            if (capturing) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            startForeground(NOTIF_ID, n, type);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    // ---------- fereastra plutitoare ----------

    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    private Rect screen() {
        if (Build.VERSION.SDK_INT >= 30) return wm.getCurrentWindowMetrics().getBounds();
        DisplayMetrics m = new DisplayMetrics();
        //noinspection deprecation
        wm.getDefaultDisplay().getRealMetrics(m);
        return new Rect(0, 0, m.widthPixels, m.heightPixels);
    }

    private void measure() {
        Rect s = screen();
        panelW = Math.min(Math.round(s.width() * .94f), dp(440));
        panelH = Math.min(Math.round(s.height() * .62f), dp(720));
        bubbleW = dp(96);
        bubbleH = dp(58);
    }

    @SuppressLint({"ClickableViewAccessibility", "SetTextI18n", "AddJavascriptInterface"})
    private void buildOverlay() {
        measure();
        int ink = Color.parseColor("#0c1512"), brass = Color.parseColor("#dcae55"), cream = Color.parseColor("#ece6d6");

        root = new FrameLayout(this);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(ink); pbg.setCornerRadius(dp(14)); pbg.setStroke(dp(1), Color.parseColor("#55dcae55"));
        panel.setBackground(pbg);
        panel.setClipToOutline(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), 0, dp(4), 0);
        TextView title = new TextView(this);
        title.setText("JARVIS  ·  trage ca să muți");
        title.setTextColor(brass);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).gravity = Gravity.CENTER_VERTICAL;
        title.setGravity(Gravity.CENTER_VERTICAL);
        TextView min = barButton("—", cream);
        TextView close = barButton("✕", cream);
        bar.addView(min, new LinearLayout.LayoutParams(dp(46), LinearLayout.LayoutParams.MATCH_PARENT));
        bar.addView(close, new LinearLayout.LayoutParams(dp(46), LinearLayout.LayoutParams.MATCH_PARENT));
        panel.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        web = new WebView(this);
        web.setBackgroundColor(ink);
        WebActivity.setup(web);
        web.addJavascriptInterface(new Bridge(), "AndroidJarvis");
        panel.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        // Panoul rămâne mereu vizibil (doar ascuns sub bulă când e strâns), ca pagina să nu fie oprită de sistem.
        root.addView(panel, new FrameLayout.LayoutParams(panelW, panelH));

        bubble = new TextView(this);
        bubble.setGravity(Gravity.CENTER);
        bubble.setTextColor(ink);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bubble.setMaxLines(2);
        bubble.setText("JARVIS");
        setBubbleColor(brass);
        bubble.setElevation(dp(6));
        root.addView(bubble, new FrameLayout.LayoutParams(bubbleW, bubbleH));
        bubble.setVisibility(View.GONE);

        int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        // FLAG_SECURE: fereastra lui Jarvis apare neagră în propria captură, ca să nu încurce citirea mesei.
        lp = new WindowManager.LayoutParams(panelW, panelH, type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_SECURE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        Rect s = screen();
        lp.x = (s.width() - panelW) / 2;
        lp.y = dp(60);
        savedX = s.width() - bubbleW - dp(8);
        savedY = Math.round(s.height() * .3f);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        wm.addView(root, lp);

        draggable(bar, () -> { });
        draggable(bubble, () -> setExpanded(true));
        min.setOnClickListener(v -> setExpanded(false));
        close.setOnClickListener(v -> stopSelf());

        web.loadUrl(server.base() + "/jarvis");
    }

    private TextView barButton(String t, int color) {
        TextView b = new TextView(this);
        b.setText(t);
        b.setTextColor(color);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    private void setBubbleColor(int c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(c);
        g.setCornerRadius(dp(29));
        g.setStroke(dp(2), Color.parseColor("#0c1512"));
        bubble.setBackground(g);
    }

    private void setExpanded(boolean on) {
        if (root == null || on == expanded) { if (on && root != null) bubble.setVisibility(View.GONE); return; }
        int ox = lp.x, oy = lp.y;
        expanded = on;
        Rect s = screen();
        if (on) {
            savedX = ox; savedY = oy;
            bubble.setVisibility(View.GONE);
            lp.width = panelW; lp.height = panelH;
            lp.x = clamp(ox + bubbleW - panelW, 0, s.width() - panelW);
            lp.y = clamp(oy, 0, s.height() - panelH);
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            bubble.setVisibility(View.VISIBLE);
            lp.width = bubbleW; lp.height = bubbleH;
            lp.x = clamp(savedX, 0, s.width() - bubbleW);
            lp.y = clamp(savedY, 0, s.height() - bubbleH);
            // strânsă nu ia tastatura și nu oprește butonul Înapoi al aplicației de dedesubt
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, Math.max(lo, v))); }

    @SuppressLint("ClickableViewAccessibility")
    private void draggable(View v, Runnable onTap) {
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        v.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; int ox, oy; boolean moved;
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); ox = lp.x; oy = lp.y; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (!moved && Math.hypot(dx, dy) < slop) return true;
                        moved = true;
                        Rect s = screen();
                        lp.x = clamp(ox + Math.round(dx), 0, s.width() - lp.width);
                        lp.y = clamp(oy + Math.round(dy), 0, s.height() - lp.height);
                        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) onTap.run();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void showAdvice(String type, String label) {
        if (bubble == null) return;
        int c; String word;
        switch (type) {
            case "fold": c = Color.parseColor("#e0735c"); word = "FOLD"; break;
            case "check": c = Color.parseColor("#86d6a1"); word = "CHECK"; break;
            case "call": c = Color.parseColor("#86d6a1"); word = "CALL"; break;
            case "raise": c = Color.parseColor("#dcae55"); word = "RAISE"; break;
            default: c = Color.parseColor("#5d6e67"); word = "…"; break;
        }
        String text = word;
        if (!label.isEmpty() && !label.equalsIgnoreCase(word)) {
            // „Call 40” / „Raise la 120” / „All-in 900” → al doilea rând cu suma
            String rest = label.replaceFirst("(?i)^(call|raise la|bet|all-in|check|fold)\\s*", "").trim();
            if (label.toLowerCase().startsWith("all-in")) word = "ALL-IN";
            text = rest.isEmpty() ? word : word + "\n" + rest;
        }
        bubble.setText(text);
        setBubbleColor(c);
        boolean fresh = !type.equals(lastType) && !type.equals("wait");
        lastType = type;
        if (fresh && !expanded) buzz();
    }

    private void buzz() {
        try {
            Vibrator v = getSystemService(Vibrator.class);
            if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) { }
    }

    private void js(String code) {
        main.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    /** Ce poate cere pagina lui Jarvis de la aplicație. */
    private final class Bridge {
        @JavascriptInterface public void advice(String type, String label) { main.post(() -> showAdvice(type, label)); }
        @JavascriptInterface public void startCapture() {
            main.post(() -> {
                if (projection != null) { js("window.__jvCaptureStarted&&__jvCaptureStarted()"); return; }
                setExpanded(false);   // strâng fereastra ca să se vadă masa
                Intent i = new Intent(JarvisService.this, CaptureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(i); }
                catch (Exception e) { js("window.__jvCaptureFailed&&__jvCaptureFailed('Nu pot cere captura ecranului.')"); }
            });
        }
        @JavascriptInterface public void stopCapture() { main.post(() -> stopProjection(false)); }
        @JavascriptInterface public void collapse() { main.post(() -> setExpanded(false)); }
        @JavascriptInterface public boolean isAndroid() { return true; }
    }

    // ---------- captura ecranului ----------

    private void startProjection(int code, Intent data) {
        try {
            goForeground(true);   // pe Android 14+ tipul „mediaProjection” trebuie pornit înainte de captură
            MediaProjectionManager m = getSystemService(MediaProjectionManager.class);
            projection = m.getMediaProjection(code, data);
            if (projection == null) throw new IllegalStateException("captura nu a pornit");
            capThread = new HandlerThread("jarvis-captura");
            capThread.start();
            capHandler = new Handler(capThread.getLooper());
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { main.post(() -> stopProjection(true)); }
            }, main);
            int[] sz = captureSize();
            capW = sz[0]; capH = sz[1];
            reader = newReader(capW, capH);
            display = projection.createVirtualDisplay("jarvis", capW, capH, sz[2],
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, capHandler);
            js("window.__jvCaptureStarted&&__jvCaptureStarted()");
        } catch (Exception e) {
            stopProjection(false);
            String msg = String.valueOf(e.getMessage()).replace("'", " ");
            js("window.__jvCaptureFailed&&__jvCaptureFailed('Captura nu a pornit: " + msg + "')");
        }
    }

    /** Lățime, înălțime, dpi: ecranul întreg, micșorat la cel mult MAX_CAPTURE_W pe lățime. */
    private int[] captureSize() {
        Rect s = screen();
        int w = s.width(), h = s.height();
        float k = Math.min(1f, MAX_CAPTURE_W / (float) Math.min(w, h));
        int dpi = getResources().getDisplayMetrics().densityDpi;
        return new int[]{ Math.max(2, Math.round(w * k) & ~1), Math.max(2, Math.round(h * k) & ~1), Math.max(1, Math.round(dpi * k)) };
    }

    private ImageReader newReader(int w, int h) {
        ImageReader r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        r.setOnImageAvailableListener(this::onImage, capHandler);
        return r;
    }

    // Cititorul se închide pe firul capturii, ca să nu dispară în mijlocul unei imagini.
    private void closeReader(ImageReader r) {
        if (r == null) return;
        Runnable close = () -> { try { r.close(); } catch (Exception ignored) { } };
        if (capHandler != null) capHandler.post(close); else close.run();
    }

    private void onImage(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastFrameAt < FRAME_MS) return;
            lastFrameAt = now;
            Image.Plane p = img.getPlanes()[0];
            int w = img.getWidth(), h = img.getHeight();
            int ps = p.getPixelStride(), rs = p.getRowStride();
            int bw = rs / ps;
            if (scratch == null || scratch.getWidth() != bw || scratch.getHeight() != h) {
                if (scratch != null) scratch.recycle();
                scratch = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888);
            }
            ByteBuffer buf = p.getBuffer();
            buf.rewind();
            scratch.copyPixelsFromBuffer(buf);
            Bitmap out = bw == w ? scratch : Bitmap.createBitmap(scratch, 0, 0, w, h);
            ByteArrayOutputStream jpg = new ByteArrayOutputStream(256 * 1024);
            out.compress(Bitmap.CompressFormat.JPEG, 88, jpg);
            if (out != scratch) out.recycle();
            server.setFrame(jpg.toByteArray());
        } catch (Exception ignored) {
        } finally {
            if (img != null) { try { img.close(); } catch (Exception ignored) { } }
        }
    }

    private void stopProjection(boolean notifyPage) {
        boolean was = projection != null;
        if (display != null) { display.release(); display = null; }
        if (projection != null) { MediaProjection p = projection; projection = null; try { p.stop(); } catch (Exception ignored) { } }
        closeReader(reader); reader = null;
        if (capThread != null) { capThread.quitSafely(); capThread = null; capHandler = null; }
        server.clearFrame();
        if (was && running) {
            goForeground(false);
            if (notifyPage) js("window.__jvCaptureEnded&&__jvCaptureEnded()");
        }
    }

    // ---------- rotirea ecranului ----------

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        main.postDelayed(this::relayout, 300);
    }

    private void relayout() {
        if (root == null) return;
        measure();
        FrameLayout.LayoutParams pl = (FrameLayout.LayoutParams) panel.getLayoutParams();
        pl.width = panelW; pl.height = panelH;
        panel.setLayoutParams(pl);
        Rect s = screen();
        if (expanded) { lp.width = panelW; lp.height = panelH; }
        lp.x = clamp(lp.x, 0, s.width() - lp.width);
        lp.y = clamp(lp.y, 0, s.height() - lp.height);
        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) { }

        if (display != null && capHandler != null) {
            int[] sz = captureSize();
            if (sz[0] != capW || sz[1] != capH) {
                capW = sz[0]; capH = sz[1];
                ImageReader old = reader;
                reader = newReader(capW, capH);
                display.resize(capW, capH, sz[2]);
                display.setSurface(reader.getSurface());
                closeReader(old);
            }
        }
    }

    static void start(Context c) {
        c.startForegroundService(new Intent(c, JarvisService.class).setAction(ACTION_SHOW));
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, JarvisService.class));
    }
}
