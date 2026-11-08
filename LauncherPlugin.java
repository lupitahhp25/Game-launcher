package com.gamelauncher.app;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
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
