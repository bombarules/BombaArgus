package com.bomba.alltogether;

import android.app.WallpaperManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Live wallpaper: Bomba's spinning soccer-ball frame with the app icons, drawn natively. */
public class BombaWallpaper extends WallpaperService {

    /** name, icon, color, page to open inside Bomba ("" = not published yet). Same order as the ball. */
    static final String[][] APPS = {
        {"AquaticsTechApp", "wave", "#9cc5bf", "apps/aquatics-tech.html"},
        {"WinterizingPoolEquipment", "flake", "#b6c4d6", ""},
        {"PropertyResource", "house", "#d9bb92", "apps/parks-map.html"},
        {"FieldReportApp", "clip", "#abc4a4", "apps/field-reports.html"},
        {"EmployeeCommunityBook", "book", "#e2aba3", ""},
        {"OddsApp", "dice", "#e5c48a", ""},
        {"SnowKnows", "cloud", "#d6cbe2", ""},
        {"Wheelscache", "wheel", "#e6b9a4", ""},
    };

    @Override
    public Engine onCreateEngine() { return new BallEngine(); }

    // ---------- geometry (built once) ----------
    static final double PHI = (1 + Math.sqrt(5)) / 2;
    static double[][] V;          // 60 vertices, circumradius 1
    static int[][] E;             // 90 edges
    static int[][] FACES;         // chosen hexagon faces (6 vertex indices each), one per app
    static double[][] FACE_N;     // face normals

    static {
        List<double[]> raw = new ArrayList<>();
        int[] s = {1, -1};
        for (int a : s) for (int b : s) cyc(raw, 0, a, b * 3 * PHI);
        for (int a : s) for (int b : s) for (int c : s) {
            cyc(raw, a, b * (2 + PHI), c * 2 * PHI);
            cyc(raw, a * PHI, b * 2, c * (2 * PHI + 1));
        }
        List<double[]> uniq = new ArrayList<>();
        for (double[] p : raw) {
            boolean dup = false;
            for (double[] u : uniq) if (dist(p, u) < 1e-6) { dup = true; break; }
            if (!dup) uniq.add(p);
        }
        double R = len(uniq.get(0));
        V = new double[uniq.size()][];
        for (int i = 0; i < V.length; i++) { double[] p = uniq.get(i); V[i] = new double[]{p[0] / R, p[1] / R, p[2] / R}; }
        double el = 2 / R;
        List<int[]> ed = new ArrayList<>();
        for (int i = 0; i < V.length; i++) for (int j = i + 1; j < V.length; j++)
            if (Math.abs(dist(V[i], V[j]) - el) < 1e-4) ed.add(new int[]{i, j});
        E = ed.toArray(new int[0][]);

        // hexagon face directions = dodecahedron vertices
        List<double[]> hd = new ArrayList<>();
        for (int a : s) for (int b : s) for (int c : s) hd.add(norm(new double[]{a, b, c}));
        for (int a : s) for (int b : s) {
            hd.add(norm(new double[]{0, a * PHI, b / PHI}));
            hd.add(norm(new double[]{a * PHI, b / PHI, 0}));
            hd.add(norm(new double[]{b / PHI, 0, a * PHI}));
        }
        List<Integer> chosen = new ArrayList<>();
        for (int i = 0; i < hd.size(); i++) { double[] d = hd.get(i); if (d[2] > .5 && d[1] > 0) { chosen.add(i); break; } }
        while (chosen.size() < APPS.length) {
            int best = -1; double bd = -1;
            for (int i = 0; i < hd.size(); i++) {
                if (chosen.contains(i)) continue;
                double m = 9;
                for (int c : chosen) m = Math.min(m, dist(hd.get(i), hd.get(c)));
                if (m > bd) { bd = m; best = i; }
            }
            chosen.add(best);
        }
        FACES = new int[APPS.length][];
        FACE_N = new double[APPS.length][];
        for (int k = 0; k < APPS.length; k++) {
            final double[] d = hd.get(chosen.get(k));
            Integer[] idx = new Integer[V.length];
            for (int i = 0; i < V.length; i++) idx[i] = i;
            Arrays.sort(idx, (p, q) -> Double.compare(dot(V[q], d), dot(V[p], d)));
            int[] f = new int[6];
            for (int i = 0; i < 6; i++) f[i] = idx[i];
            // order the six corners around the face
            final double[] ctr = new double[3];
            for (int i : f) for (int a = 0; a < 3; a++) ctr[a] += V[i][a] / 6;
            double[] u = norm(sub(V[f[0]], ctr)), w = cross(d, u);
            Integer[] ord = {f[0], f[1], f[2], f[3], f[4], f[5]};
            Arrays.sort(ord, (p, q) -> Double.compare(ang(V[p], ctr, u, w), ang(V[q], ctr, u, w)));
            for (int i = 0; i < 6; i++) f[i] = ord[i];
            FACES[k] = f;
            FACE_N[k] = d;
        }
    }
    static void cyc(List<double[]> l, double a, double b, double c) { l.add(new double[]{a, b, c}); l.add(new double[]{c, a, b}); l.add(new double[]{b, c, a}); }
    static double dist(double[] a, double[] b) { return Math.sqrt(sq(a[0] - b[0]) + sq(a[1] - b[1]) + sq(a[2] - b[2])); }
    static double sq(double x) { return x * x; }
    static double len(double[] a) { return Math.sqrt(dot(a, a)); }
    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double[] norm(double[] a) { double l = len(a); return new double[]{a[0] / l, a[1] / l, a[2] / l}; }
    static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    static double ang(double[] p, double[] c, double[] u, double[] w) { double[] v = sub(p, c); return Math.atan2(dot(v, w), dot(v, u)); }

    // ---------- engine ----------
    class BallEngine extends Engine {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable tick = this::drawFrame;
        private boolean visible;
        private int W, H;
        private Bitmap bg;
        private final long start = SystemClock.uptimeMillis();
        private final Paint edgeP = new Paint(Paint.ANTI_ALIAS_FLAG), panelP = new Paint(Paint.ANTI_ALIAS_FLAG),
            ringP = new Paint(Paint.ANTI_ALIAS_FLAG), iconS = new Paint(Paint.ANTI_ALIAS_FLAG), iconF = new Paint(Paint.ANTI_ALIAS_FLAG);
        // projected face shapes for tap testing
        private final float[][] faceXY = new float[APPS.length][12];
        private final boolean[] faceFront = new boolean[APPS.length];

        BallEngine() {
            edgeP.setStyle(Paint.Style.STROKE); edgeP.setStrokeCap(Paint.Cap.ROUND);
            panelP.setStyle(Paint.Style.FILL);
            ringP.setStyle(Paint.Style.STROKE); ringP.setStrokeJoin(Paint.Join.ROUND);
            iconS.setStyle(Paint.Style.STROKE); iconS.setStrokeCap(Paint.Cap.ROUND); iconS.setStrokeJoin(Paint.Join.ROUND); iconS.setStrokeWidth(20);
            iconF.setStyle(Paint.Style.FILL);
        }

        @Override public void onCreate(SurfaceHolder h) { super.onCreate(h); setTouchEventsEnabled(false); }
        @Override public void onDestroy() { super.onDestroy(); handler.removeCallbacks(tick); }
        @Override public void onVisibilityChanged(boolean v) {
            visible = v;
            if (v) drawFrame(); else handler.removeCallbacks(tick);   // paused while hidden: saves battery
        }
        @Override public void onSurfaceChanged(SurfaceHolder h, int f, int w, int hh) {
            super.onSurfaceChanged(h, f, w, hh); W = w; H = hh; makeBackground(); drawFrame();
        }
        @Override public void onSurfaceDestroyed(SurfaceHolder h) { super.onSurfaceDestroyed(h); visible = false; handler.removeCallbacks(tick); }

        private void makeBackground() {
            if (W <= 0 || H <= 0) return;
            if (bg != null) bg.recycle();
            bg = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bg);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(0, 0, 0, H, Color.parseColor("#1a1219"), Color.parseColor("#120d14"), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, H, p);
            float m = Math.max(W, H);
            glow(c, W * .25f, H * .3f, m * .45f, Color.argb(70, 120, 66, 80));
            glow(c, W * .8f, H * .62f, m * .5f, Color.argb(60, 84, 70, 120));
            glow(c, W * .5f, H * .9f, m * .4f, Color.argb(45, 140, 96, 70));
            Random r = new Random(7);
            Paint st = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (int i = 0; i < 180; i++) {
                st.setColor(Color.argb(40 + r.nextInt(110), 240, 220, 196));
                c.drawCircle(r.nextFloat() * W, r.nextFloat() * H, .6f + r.nextFloat() * 1.4f, st);
            }
        }
        private void glow(Canvas c, float x, float y, float rad, int col) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new RadialGradient(x, y, rad, col, Color.TRANSPARENT, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, rad, p);
        }

        // working buffers
        private final double[][] P = new double[60][3];     // rotated
        private final float[][] S = new float[60][2];        // screen
        private final double D = 4.2;                        // camera distance

        private void drawFrame() {
            handler.removeCallbacks(tick);
            SurfaceHolder h = getSurfaceHolder();
            Canvas c = null;
            try {
                c = h.lockCanvas();
                if (c != null) render(c);
            } catch (Exception ignored) {
            } finally {
                if (c != null) try { h.unlockCanvasAndPost(c); } catch (Exception ignored) { }
            }
            if (visible) handler.postDelayed(tick, 33);   // about 30 frames a second
        }

        private void render(Canvas c) {
            if (bg != null) c.drawBitmap(bg, 0, 0, null); else c.drawColor(Color.parseColor("#140f16"));
            double t = (SystemClock.uptimeMillis() - start) / 1000.0;
            double yaw = t * .18, pitch = .22;
            double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
            float cx = W / 2f, cyS = H * .46f;
            double f = Math.min(W, H) * .36 * (D - 1) * 1.05;
            for (int i = 0; i < V.length; i++) {
                double x = V[i][0] * cy + V[i][2] * sy, z = -V[i][0] * sy + V[i][2] * cy, y = V[i][1];
                double y2 = y * cp - z * sp, z2 = y * sp + z * cp;
                P[i][0] = x; P[i][1] = y2; P[i][2] = z2;
                double k = f / (D - z2);
                S[i][0] = (float) (cx + x * k); S[i][1] = (float) (cyS - y2 * k);
            }
            // gather drawables: edges and app faces, painted far to near
            int n = E.length + APPS.length;
            double[] depth = new double[n];
            Integer[] order = new Integer[n];
            for (int i = 0; i < E.length; i++) { depth[i] = (P[E[i][0]][2] + P[E[i][1]][2]) / 2; order[i] = i; }
            for (int k = 0; k < APPS.length; k++) {
                double z = 0; for (int v : FACES[k]) z += P[v][2];
                depth[E.length + k] = z / 6 + .001; order[E.length + k] = E.length + k;
            }
            Arrays.sort(order, (a, b) -> Double.compare(depth[a], depth[b]));
            for (int o : order) {
                if (o < E.length) drawEdge(c, o, f); else drawFace(c, o - E.length, f, cy, sy, cp, sp);
            }
        }

        private void drawEdge(Canvas c, int i, double f) {
            int a = E[i][0], b = E[i][1];
            double z = (P[a][2] + P[b][2]) / 2;
            double near = (z + 1) / 2;   // 0 far .. 1 near
            edgeP.setStrokeWidth((float) (.038 * f / (D - z)));
            int shade = (int) (90 + 70 * near);
            edgeP.setColor(Color.argb((int) (110 + 145 * near), Math.min(255, shade + 51), Math.min(255, shade + 32), Math.min(255, shade + 46)));
            c.drawLine(S[a][0], S[a][1], S[b][0], S[b][1], edgeP);
        }

        private void drawFace(Canvas c, int k, double f, double cy, double sy, double cp, double sp) {
            int[] fv = FACES[k];
            double[] nn = FACE_N[k];
            double x = nn[0] * cy + nn[2] * sy, z = -nn[0] * sy + nn[2] * cy, y = nn[1];
            double nz = y * sp + z * cp;           // facing the viewer when > 0
            boolean front = nz > 0;
            faceFront[k] = front;
            float mx = 0, my = 0; double mz = 0;
            for (int v : fv) { mx += S[v][0] / 6; my += S[v][1] / 6; mz += P[v][2] / 6; }
            Path poly = new Path(), ring = new Path();
            for (int i = 0; i < 6; i++) {
                float px = S[fv[i]][0], py = S[fv[i]][1];
                faceXY[k][i * 2] = px; faceXY[k][i * 2 + 1] = py;
                float ix = mx + (px - mx) * .92f, iy = my + (py - my) * .92f;
                float rx = mx + (px - mx) * .86f, ry = my + (py - my) * .86f;
                if (i == 0) { poly.moveTo(ix, iy); ring.moveTo(rx, ry); } else { poly.lineTo(ix, iy); ring.lineTo(rx, ry); }
            }
            poly.close(); ring.close();
            float fade = front ? 1f : .38f;
            panelP.setColor(Color.argb((int) (209 * fade), 0x24, 0x1a, 0x21));
            c.drawPath(poly, panelP);
            int col = Color.parseColor(APPS[k][2]);
            double R0 = dist(V[fv[0]], centroid(fv));
            float scale = (float) (f / (D - mz));
            ringP.setStrokeWidth((float) (R0 * .1 * scale));
            ringP.setColor(withAlpha(col, (int) (255 * fade)));
            c.drawPath(ring, ringP);
            // icon (kept upright and readable from either side)
            float px = (float) (R0 * 1.15 * scale) / 256f * .62f;
            float squash = (float) Math.max(.35, Math.abs(nz));
            c.save();
            c.translate(mx, my);
            c.scale(px, px * (.55f + .45f * squash));
            iconS.setColor(withAlpha(col, (int) (255 * fade)));
            iconF.setColor(withAlpha(col, (int) (255 * fade)));
            Icons.draw(c, APPS[k][1], iconS, iconF);
            c.restore();
        }

        private double[] centroid(int[] fv) {
            double[] m = new double[3];
            for (int v : fv) for (int a = 0; a < 3; a++) m[a] += V[v][a] / 6;
            return m;
        }

        /** A tap on the wallpaper (where the home screen passes it through) opens that app in Bomba. */
        @Override
        public Bundle onCommand(String action, int x, int y, int z, Bundle extras, boolean resultRequested) {
            if (WallpaperManager.COMMAND_TAP.equals(action)) {
                for (int k = 0; k < APPS.length; k++) {
                    if (!faceFront[k] || APPS[k][3].isEmpty()) continue;
                    if (inside(faceXY[k], x, y)) {
                        Intent i = new Intent(BombaWallpaper.this, MainActivity.class);
                        i.putExtra("open", APPS[k][3]);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        try { startActivity(i); } catch (Exception ignored) { }
                        break;
                    }
                }
            }
            return null;
        }

        private boolean inside(float[] xy, float x, float y) {
            boolean in = false;
            for (int i = 0, j = 5; i < 6; j = i++) {
                float xi = xy[i * 2], yi = xy[i * 2 + 1], xj = xy[j * 2], yj = xy[j * 2 + 1];
                if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) in = !in;
            }
            return in;
        }
    }

    static int withAlpha(int c, int a) { return Color.argb(Math.max(0, Math.min(255, a)), Color.red(c), Color.green(c), Color.blue(c)); }

    /** The eight app icons, in the same 256-unit coordinates as the web version. */
    static class Icons {
        static void draw(Canvas c, String id, Paint s, Paint f) {
            Path p = new Path();
            switch (id) {
                case "wave":
                    for (int k = -1; k <= 1; k++) {
                        Path w = new Path();
                        for (int x = -120; x <= 120; x += 4) {
                            float y = (float) (k * 62 + Math.sin(x / 30.0) * 20);
                            if (x == -120) w.moveTo(x, y); else w.lineTo(x, y);
                        }
                        c.drawPath(w, s);
                    }
                    return;
                case "flake":
                    for (int k = 0; k < 6; k++) {
                        c.save(); c.rotate(k * 60);
                        c.drawLine(0, 0, 0, -125, s); c.drawLine(0, -70, -30, -100, s); c.drawLine(0, -70, 30, -100, s);
                        c.restore();
                    }
                    return;
                case "house":
                    p.moveTo(-125, -5); p.lineTo(0, -115); p.lineTo(125, -5);
                    p.moveTo(-90, -35); p.lineTo(-90, 115); p.lineTo(90, 115); p.lineTo(90, -35);
                    p.moveTo(-28, 115); p.lineTo(-28, 40); p.lineTo(28, 40); p.lineTo(28, 115);
                    c.drawPath(p, s); return;
                case "clip":
                    c.drawRoundRect(new RectF(-90, -110, 90, 125), 22, 22, s);
                    p.moveTo(-40, -110); p.lineTo(-40, -135); p.lineTo(40, -135); p.lineTo(40, -110);
                    p.moveTo(-50, 10); p.lineTo(-12, 48); p.lineTo(55, -30);
                    c.drawPath(p, s); return;
                case "book":
                    p.moveTo(0, -80); p.quadTo(-60, -115, -130, -90); p.lineTo(-130, 95); p.quadTo(-60, 70, 0, 105);
                    p.quadTo(60, 70, 130, 95); p.lineTo(130, -90); p.quadTo(60, -115, 0, -80); p.lineTo(0, 105);
                    c.drawPath(p, s); return;
                case "dice":
                    c.drawRoundRect(new RectF(-115, -115, 115, 115), 40, 40, s);
                    int[][] d = {{-55, -55}, {55, 55}, {0, 0}, {55, -55}, {-55, 55}};
                    for (int[] q : d) c.drawCircle(q[0], q[1], 18, f);
                    return;
                case "cloud":
                    p.moveTo(-95, 10);
                    p.arcTo(new RectF(-110, -65, -30, 15), 108, 162, false);
                    p.arcTo(new RectF(-57, -117, 67, 7), 198, 153, false);
                    p.arcTo(new RectF(36, -57, 120, 27), 252, 189, false);
                    p.close();
                    c.drawPath(p, s);
                    int[][] dots = {{-60, 70}, {0, 95}, {60, 70}, {-30, 130}, {30, 130}};
                    for (int[] q : dots) c.drawCircle(q[0], q[1], 12, f);
                    return;
                case "wheel":
                    c.drawCircle(0, 0, 120, s); c.drawCircle(0, 0, 32, s);
                    for (int k = 0; k < 6; k++) {
                        double a = k * Math.PI / 3;
                        c.drawLine((float) (Math.cos(a) * 32), (float) (Math.sin(a) * 32), (float) (Math.cos(a) * 120), (float) (Math.sin(a) * 120), s);
                    }
                    return;
                default:
            }
        }
    }
}
