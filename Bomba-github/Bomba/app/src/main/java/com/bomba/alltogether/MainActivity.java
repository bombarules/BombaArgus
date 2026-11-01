package com.bomba.alltogether;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.media.projection.MediaProjectionManager;
import android.Manifest;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final int PICK_FILE = 41;
    private static final int SHARE_REQ = 42;
    private String shareCode, shareName;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#140F16"));
        w.setNavigationBarColor(Color.parseColor("#140F16"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#140F16"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // window.open() and target links become normal navigations handled below.
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);

        web.addJavascriptInterface(new NativeBridge(), "BombaNative");
        ScreenShareService.listener = json -> runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript("window.bombaShareStatus&&bombaShareStatus(" + json + ")", null);
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return openOutside(req.getUrl());
            }
        });

        // Photo picker, camera and backup-file picker for <input type="file">.
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                cameraUri = null;
                Intent pick = params.createIntent();
                Intent chooser;
                boolean wantsImage = false;
                for (String t : params.getAcceptTypes()) if (t != null && t.startsWith("image")) wantsImage = true;
                if (wantsImage && params.isCaptureEnabled()) {
                    Intent cam = cameraIntent();
                    chooser = cam != null ? cam : pick;
                } else if (wantsImage) {
                    chooser = Intent.createChooser(pick, "Add photo");
                    Intent cam = cameraIntent();
                    if (cam != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
                } else {
                    chooser = Intent.createChooser(pick, "Choose file");
                }
                try {
                    startActivityForResult(chooser, PICK_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        setContentView(web);
        hideBars();
        web.loadUrl(startUrl(getIntent()));
    }

    private static final String HOME_URL = "file:///android_asset/index.html";

    /** The live wallpaper can ask Bomba to open one of the bundled apps directly. */
    private String startUrl(Intent i) {
        String open = i != null ? i.getStringExtra("open") : null;
        if (open != null && open.startsWith("apps/")) return "file:///android_asset/" + open;
        return HOME_URL;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (web == null) return;
        String url = startUrl(intent);
        if (!url.equals(HOME_URL)) { web.loadUrl(url); return; }
        // Home button pressed: go back to the ball.
        String cur = web.getUrl();
        if (cur != null && cur.equals(HOME_URL)) web.evaluateJavascript("window.bombaHome&&bombaHome()", null);
        else web.loadUrl(HOME_URL);
    }

    private Intent cameraIntent() {
        try {
            File dir = new File(getCacheDir(), "shared");
            dir.mkdirs();
            File f = new File(dir, "photo-" + System.currentTimeMillis() + ".jpg");
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            cam.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            cam.setClipData(ClipData.newRawUri("", cameraUri));
            return cam;
        } catch (Exception e) {
            cameraUri = null;
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SHARE_REQ) {
            if (resultCode != RESULT_OK || data == null) {
                web.evaluateJavascript("window.bombaShareStatus&&bombaShareStatus({state:'error',message:'Sharing was cancelled.'})", null);
                return;
            }
            Intent svc = new Intent(this, ScreenShareService.class)
                .putExtra(ScreenShareService.EXTRA_CODE, shareCode)
                .putExtra(ScreenShareService.EXTRA_NAME, shareName)
                .putExtra(ScreenShareService.EXTRA_RESULT, resultCode)
                .putExtra(ScreenShareService.EXTRA_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
            return;
        }
        if (requestCode != PICK_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            } else if (data != null && data.getClipData() != null) {
                ClipData c = data.getClipData();
                result = new Uri[c.getItemCount()];
                for (int i = 0; i < c.getItemCount(); i++) result[i] = c.getItemAt(i).getUri();
            } else if (cameraUri != null) {
                result = new Uri[]{cameraUri};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    private String iconData(Drawable d) {
        int n = 96;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        b.recycle();
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    /** Lets the page hand a file to Android's share menu (pick OneDrive to save it there). */
    private class NativeBridge {
        /** Lists the phone's apps for Bomba's swipe-up drawer (sent back to the page as bombaApps(list)). */
        @JavascriptInterface
        public void requestApps() {
            new Thread(() -> {
                try {
                    PackageManager pm = getPackageManager();
                    Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                    List<ResolveInfo> found = pm.queryIntentActivities(q, 0);
                    List<JSONObject> rows = new ArrayList<>();
                    for (ResolveInfo r : found) {
                        String pkg = r.activityInfo.packageName;
                        if (pkg.equals(getPackageName())) continue;
                        JSONObject o = new JSONObject();
                        o.put("label", String.valueOf(r.loadLabel(pm)));
                        o.put("pkg", pkg);
                        o.put("icon", iconData(r.loadIcon(pm)));
                        rows.add(o);
                    }
                    Collections.sort(rows, (a, b) -> a.optString("label").compareToIgnoreCase(b.optString("label")));
                    JSONArray arr = new JSONArray(rows);
                    String js = "window.bombaApps&&bombaApps(" + arr.toString() + ")";
                    runOnUiThread(() -> web.evaluateJavascript(js, null));
                } catch (Exception ignored) { }
            }).start();
        }

        @JavascriptInterface
        public void launch(String pkg) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            runOnUiThread(() -> { try { startActivity(i); } catch (Exception ignored) { } });
        }

        /** Bomba Live: asks Android for permission to share the screen, then starts sharing. */
        @JavascriptInterface
        public void startScreenShare(String code, String name) {
            runOnUiThread(() -> {
                shareCode = code; shareName = name;
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 43);
                MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                try { startActivityForResult(mpm.createScreenCaptureIntent(), SHARE_REQ); }
                catch (Exception e) { web.evaluateJavascript("window.bombaShareStatus&&bombaShareStatus({state:'error',message:'This phone cannot share its screen.'})", null); }
            });
        }

        @JavascriptInterface
        public void stopScreenShare() {
            runOnUiThread(() -> { try { startService(new Intent(MainActivity.this, ScreenShareService.class).setAction("stop")); } catch (Exception ignored) { } });
        }

        @JavascriptInterface
        public String shareState() { return ScreenShareService.status(); }

        @JavascriptInterface
        public void setKeepAwake(boolean on) { runOnUiThread(() -> { if (web != null) web.setKeepScreenOn(on); }); }

        /** Opens Android's screen for setting Bomba as the live wallpaper. */
        @JavascriptInterface
        public void pickWallpaper() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
                    i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        new ComponentName(MainActivity.this, BombaWallpaper.class));
                    startActivity(i);
                } catch (Exception e) {
                    try { startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)); } catch (Exception ignored) { }
                }
            });
        }

        /** Opens Android's default-home-app setting so Bomba can be picked as the home screen. */
        @JavascriptInterface
        public void homeSettings() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)); }
                catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) { } }
            });
        }

        @JavascriptInterface
        public void share(String name, String mime, String text) {
            try {
                File dir = new File(getCacheDir(), "shared");
                dir.mkdirs();
                String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
                File f = new File(dir, safe);
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                }
                Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime);
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_TITLE, safe);
                send.setClipData(ClipData.newRawUri(safe, uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(send, "Save to");
                runOnUiThread(() -> startActivity(chooser));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Could not share the file", Toast.LENGTH_SHORT).show());
            }
        }
    }

    /** Keeps the ball and bundled apps inside Bomba; every other link opens outside it. */
    private boolean openOutside(Uri uri) {
        if ("file".equals(uri.getScheme())) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) { }
        return true;
    }

    /** Full screen, borderless: hides the status bar and navigation bar. Swipe from an edge to peek at them. */
    @SuppressWarnings("deprecation")
    private void hideBars() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @Override
    public void onBackPressed() {
        if (web == null) { super.onBackPressed(); return; }
        String cur = web.getUrl();
        if (web.canGoBack()) { web.goBack(); return; }
        if (cur != null && !cur.equals(HOME_URL)) { web.loadUrl(HOME_URL); return; }
        // On the ball itself: as the home screen there's nowhere to go back to, so just close panels.
        if (isHome()) web.evaluateJavascript("window.bombaHome&&bombaHome()", null);
        else super.onBackPressed();
    }

    private boolean isHome() {
        Intent h = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo r = getPackageManager().resolveActivity(h, PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
    }
}
