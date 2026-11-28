package ro.masadepoker;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

/** Fereastră invizibilă care cere permisiunea Android de captură a ecranului și o dă serviciului. */
public class CaptureActivity extends Activity {
    private static final int REQ = 7;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) return;
        MediaProjectionManager m = getSystemService(MediaProjectionManager.class);
        try { startActivityForResult(m.createScreenCaptureIntent(), REQ); }
        catch (Exception e) { send(RESULT_CANCELED, null); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ) send(res, data);
    }

    private void send(int res, Intent data) {
        Intent i = new Intent(this, JarvisService.class)
                .setAction(JarvisService.ACTION_PROJECTION)
                .putExtra(JarvisService.EXTRA_CODE, res);
        if (data != null) i.putExtra(JarvisService.EXTRA_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        finish();
        overridePendingTransition(0, 0);
    }
}
