package io.github.yuedaochangmendian.skybox;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import io.nekohasekai.libbox.Libbox;

/** Private, validated sing-box profiles. No configuration is written to shared storage. */
public final class ProfileStore {
    public static final int MAX_CONFIG_BYTES = 2 * 1024 * 1024;
    private static final String PREFS = "skybox_profiles";
    private static final String ACTIVE = "active_id";

    public static final class Profile {
        public final String id;
        public final String name;
        public final long modified;

        private Profile(String id, String name, long modified) {
            this.id = id;
            this.name = name;
            this.modified = modified;
        }
    }

    private ProfileStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File directory(Context context) throws IOException {
        File dir = new File(context.getFilesDir(), "profiles");
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("无法创建私有配置目录");
        }
        return dir;
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static AtomicFile file(Context context, String id) throws IOException {
        if (!validId(id)) throw new IOException("配置标识无效");
        return new AtomicFile(new File(directory(context), id + ".json"));
    }

    public static synchronized List<Profile> list(Context context) throws IOException {
        File[] files = directory(context).listFiles();
        if (files == null) throw new IOException("无法读取配置目录");
        List<Profile> result = new ArrayList<>();
        SharedPreferences storage = prefs(context);
        for (File entry : files) {
            String name = entry.getName();
            // AtomicFile may leave a backup after process death; openRead recovers it.
            String suffix = name.endsWith(".json.bak") ? ".json.bak" : ".json";
            if (!name.endsWith(suffix)) continue;
            String id = name.substring(0, name.length() - suffix.length());
            if (!validId(id)) continue;
            boolean duplicate = false;
            for (Profile profile : result) if (profile.id.equals(id)) duplicate = true;
            if (!duplicate) result.add(new Profile(id,
                    storage.getString("name_" + id, "未命名配置"), entry.lastModified()));
        }
        Collections.sort(result, (a, b) -> Long.compare(b.modified, a.modified));
        return result;
    }

    public static synchronized String activeId(Context context) {
        return prefs(context).getString(ACTIVE, "");
    }

    /** Empty means no readable selected profile; the service must refuse to start. */
    public static synchronized String activeConfig(Context context) {
        String id = activeId(context);
        if (!validId(id)) return "";
        try {
            return read(context, id);
        } catch (IOException e) {
            return "";
        }
    }

    public static synchronized String activeName(Context context) {
        String id = activeId(context);
        if (!validId(id)) return "未选择配置";
        return prefs(context).getString("name_" + id, "未命名配置");
    }

    public static synchronized String read(Context context, String id) throws IOException {
        try (FileInputStream input = file(context, id).openRead();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_CONFIG_BYTES) throw new IOException("配置超过 2 MiB 限制");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Run on a worker thread. Successful validation is required before every write. */
    public static void validate(Context context, String config) throws Exception {
        if (config == null || config.trim().isEmpty()) throw new IOException("配置内容为空");
        if (config.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
            throw new IOException("配置超过 2 MiB 限制");
        }
        JSONObject document = new JSONObject(config);
        JSONObject route = document.optJSONObject("route");
        if (route == null || !route.optBoolean("auto_detect_interface", false)) {
            throw new IOException("Android VPN 配置需要 route.auto_detect_interface=true，以避免内核连接重新进入 VPN");
        }
        CoreRuntime.ensureInit(context.getApplicationContext());
        Libbox.checkConfig(config);
    }

    /** Null id creates a new profile. Saving preserves the selected profile unless none exists. */
    public static synchronized String save(Context context, String id, String name, String config)
            throws Exception {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) throw new IOException("请填写配置名称");
        if (name.length() > 80) throw new IOException("配置名称最多 80 个字符");
        validate(context, config);
        boolean create = id == null;
        if (create) id = UUID.randomUUID().toString();
        AtomicFile target = file(context, id);
        if (!create && !target.getBaseFile().exists()
                && !new File(target.getBaseFile().getPath() + ".bak").exists()) {
            throw new IOException("配置已被删除，请重新新建");
        }
        FileOutputStream stream = null;
        try {
            stream = target.startWrite();
            stream.write(config.getBytes(StandardCharsets.UTF_8));
            target.finishWrite(stream);
        } catch (IOException e) {
            if (stream != null) target.failWrite(stream);
            throw e;
        }
        SharedPreferences.Editor edit = prefs(context).edit().putString("name_" + id, name);
        if (activeId(context).isEmpty()) edit.putString(ACTIVE, id);
        if (!edit.commit()) throw new IOException("配置已写入，但名称或选用状态保存失败，请重新选择");
        return id;
    }

    public static synchronized void select(Context context, String id) throws IOException {
        read(context, id); // Recover AtomicFile and reject absent/unreadable profiles.
        if (!prefs(context).edit().putString(ACTIVE, id).commit()) {
            throw new IOException("无法保存选用状态");
        }
    }

    public static synchronized void delete(Context context, String id) throws IOException {
        AtomicFile target = file(context, id);
        if (id.equals(activeId(context))) {
            if (!prefs(context).edit().remove(ACTIVE).commit()) throw new IOException("无法清除选用状态");
        }
        target.delete();
        if (target.getBaseFile().exists()
                || new File(target.getBaseFile().getPath() + ".bak").exists()) {
            throw new IOException("删除配置失败");
        }
        if (!prefs(context).edit().remove("name_" + id).commit()) {
            throw new IOException("配置已删除，名称记录清理失败");
        }
    }
}
