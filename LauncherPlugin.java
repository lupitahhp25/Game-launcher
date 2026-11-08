package com.gamelauncher.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.util.HashSet;
import java.util.Set;

@CapacitorPlugin(name = "Launcher")
public class LauncherPlugin extends Plugin {

    private String safUri(String path, String tree) {
        String base = "/storage/emulated/0/";
        if (path == null || !path.startsWith(base)) return null;
        String rel = path.substring(base.length());
        String auth = "content://com.android.externalstorage.documents/";
        if (tree != null && tree.startsWith(base) && path.startsWith(tree)) {
            String t = tree.substring(base.length());
            while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
            return auth + "tree/primary%3A" + Uri.encode(t) + "/document/primary%3A" + Uri.encode(rel);
        }
        return auth + "document/primary%3A" + Uri.encode(rel);
    }

    private String findByHint(String hint) {
        if (hint == null) return null;
        PackageManager pm = getContext().getPackageManager();
        String h = hint.toLowerCase();
        for (android.content.pm.ApplicationInfo a : pm.getInstalledApplications(0)) {
            String label = String.valueOf(pm.getApplicationLabel(a)).toLowerCase();
            if (a.packageName.toLowerCase().contains(h) || label.contains(h)) {
                if (pm.getLaunchIntentForPackage(a.packageName) != null) return a.packageName;
            }
        }
        return null;
    }

    @PluginMethod
    public void launch(PluginCall call) {
        String pkg = call.getString("package");
        if (pkg == null) { call.reject("Falta el package"); return; }
        String activity = call.getString("activity");
        String action = call.getString("action", "android.intent.action.MAIN");
        String data = call.getString("data");
        String extraKey = call.getString("extraKey");
        String path = call.getString("path");
        String hint = call.getString("hint");
        String tree = call.getString("tree");
        boolean saf = Boolean.TRUE.equals(call.getBoolean("saf", false));

        JSObject ret = new JSObject();
        PackageManager pm = getContext().getPackageManager();
        try {
            if (pm.getLaunchIntentForPackage(pkg) == null) {
                String alt = findByHint(hint);
                if (alt != null) pkg = alt;
            }
            if (activity == null && data == null && extraKey == null && !saf) {
                throw new Exception("usar-launch-normal");
            }
            Intent i = new Intent(action);
            if (activity != null) i.setComponent(new ComponentName(pkg, activity));
            else i.setPackage(pkg);
            boolean content = false;
            if (saf) {
                String u = safUri(path, tree);
                if (u != null) { i.setData(Uri.parse(u)); content = true; }
            } else if (data != null) {
                i.setData(Uri.parse(data));
            }
            if (extraKey != null && path != null) i.putExtra(extraKey, path);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (!content) i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getContext().startActivity(i);
            ret.put("fallback", false);
            call.resolve(ret);
        } catch (Exception e) {
            try {
                Intent f = pm.getLaunchIntentForPackage(pkg);
                if (f == null) {
                    String alt = findByHint(hint);
                    if (alt != null) f = pm.getLaunchIntentForPackage(alt);
                }
                if (f == null) { call.reject("No esta instalado: " + pkg); return; }
                f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(f);
                ret.put("fallback", true);
                call.resolve(ret);
            } catch (Exception e2) {
                call.reject("No se pudo abrir: " + e2.getMessage());
            }
        }
    }

    @PluginMethod
    public void pickFolder(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(call, i, "pickFolderResult");
    }

    @ActivityCallback
    private void pickFolderResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            call.reject("Cancelado");
            return;
        }
        try {
            Uri uri = result.getData().getData();
            String docId = DocumentsContract.getTreeDocumentId(uri);
            int c = docId.indexOf(':');
            String vol = c >= 0 ? docId.substring(0, c) : docId;
            String rel = c >= 0 ? docId.substring(c + 1) : "";
            String p = vol.equals("primary") ? "/storage/emulated/0" : "/storage/" + vol;
            if (!rel.isEmpty()) p += "/" + rel;
            JSObject r = new JSObject();
            r.put("path", p);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("No se pudo leer la carpeta: " + e.getMessage());
        }
    }

    @PluginMethod
    public void listApps(PluginCall call) {
        PackageManager pm = getContext().getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> seen = new HashSet<>();
        JSArray out = new JSArray();
        for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(i, 0)) {
            android.content.pm.ApplicationInfo a = r.activityInfo.applicationInfo;
            if (a.packageName.equals(getContext().getPackageName()) || !seen.add(a.packageName)) continue;
            boolean game = (a.flags & android.content.pm.ApplicationInfo.FLAG_IS_GAME) != 0;
            if (Build.VERSION.SDK_INT >= 26 && a.category == android.content.pm.ApplicationInfo.CATEGORY_GAME) game = true;
            JSObject o = new JSObject();
            o.put("name", String.valueOf(r.loadLabel(pm)));
            o.put("pkg", a.packageName);
            o.put("game", game);
            out.put(o);
        }
        JSObject res = new JSObject();
        res.put("apps", out);
        call.resolve(res);
    }

    @PluginMethod
    public void appIcon(PluginCall call) {
        String pkg = call.getString("package");
        try {
            android.graphics.drawable.Drawable d = getContext().getPackageManager().getApplicationIcon(pkg);
            int n = 160;
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            d.setBounds(0, 0, n, n);
            d.draw(c);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            b.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, bos);
            JSObject r = new JSObject();
            r.put("image", "data:image/png;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP));
            call.resolve(r);
        } catch (Exception e) {
            call.reject("Sin icono: " + e.getMessage());
        }
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        try {
            Intent b = getContext().registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = b.getIntExtra("level", -1), scale = b.getIntExtra("scale", 100), st = b.getIntExtra("status", -1);
            r.put("battery", scale > 0 ? Math.round(level * 100f / scale) : -1);
            r.put("charging", st == 2 || st == 5);
        } catch (Exception e) {
            r.put("battery", -1);
            r.put("charging", false);
        }
        String net = "none";
        int lvl = 0;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            if (n != null) {
                android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c != null) {
                    if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) net = "wifi";
                    else if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) net = "cell";
                    else net = "other";
                }
            }
            if (net.equals("wifi")) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getContext().getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
                lvl = android.net.wifi.WifiManager.calculateSignalLevel(wm.getConnectionInfo().getRssi(), 5);
            }
        } catch (Exception e) {
            if (net.equals("wifi")) lvl = 3;
        }
        r.put("net", net);
        r.put("wifiLevel", lvl);
        call.resolve(r);
    }

    @PluginMethod
    public void hasAllFiles(PluginCall call) {
        boolean ok = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
        JSObject r = new JSObject();
        r.put("granted", ok);
        call.resolve(r);
    }

    @PluginMethod
    public void requestAllFiles(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Exception e) {
            Intent i = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        }
        call.resolve();
    }

    @PluginMethod
    public void scan(PluginCall call) {
        String path = call.getString("path");
        String exts = call.getString("exts", "");
        if (path == null) { call.reject("Falta la carpeta"); return; }
        File root = new File(path);
        if (!root.isDirectory()) { call.reject("Carpeta no encontrada o sin permiso: " + path); return; }
        Set<String> set = new HashSet<>();
        for (String e : exts.split(",")) if (!e.trim().isEmpty()) set.add(e.trim().toLowerCase());
        JSArray out = new JSArray();
        walk(root, set, out, 0);
        JSObject r = new JSObject();
        r.put("files", out);
        call.resolve(r);
    }

    private void walk(File dir, Set<String> exts, JSArray out, int depth) {
        File[] list = dir.listFiles();
        if (list == null || depth > 4) return;
        for (File f : list) {
            if (f.isDirectory()) { walk(f, exts, out, depth + 1); continue; }
            String n = f.getName().toLowerCase();
            int dot = n.lastIndexOf('.');
            if (dot >= 0 && exts.contains(n.substring(dot))) {
                JSObject o = new JSObject();
                o.put("name", f.getName());
                o.put("path", f.getAbsolutePath());
                out.put(o);
            }
        }
    }
}
