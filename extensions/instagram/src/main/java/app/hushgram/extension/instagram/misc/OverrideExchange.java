/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.misc;

import android.app.Activity;
import android.content.pm.PackageInfo;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import app.hushgram.extension.instagram.settings.FamilyNames;
import app.hushgram.extension.shared.diagnostics.HookStatus;
import app.hushgram.extension.shared.settings.SettingsJson;

/** A read-only native override snapshot. This class has no writer or cache-reload operation. */
public final class OverrideExchange {
    public static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_PARAMETERS = 65536, MAX_OVERRIDES = 4096;
    private static final String HOST = "com.instagram.android";
    private static final SettingsJson.Limits LIMITS = new SettingsJson.Limits(5, 16384, 10000, MAX_OVERRIDES, MAX_BYTES);
    private OverrideExchange() {}

    public static final class Parameter {
        public final int config, index, type;
        public final String configName, name;
        public final long nativeId;
        public Parameter(int config, int index, String configName, String name, int type, long nativeId) {
            this.config = config; this.index = index; this.configName = configName;
            this.name = name; this.type = type; this.nativeId = nativeId;
        }
    }

    public static final class Snapshot {
        private final String version, hash;
        private final long code;
        private final Map<Long, Parameter> parameters = new TreeMap<>();
        private final Map<Integer, String> configs = new HashMap<>();
        private final String overrides;

        Snapshot(String version, long code, List<Parameter> schema, byte[] nativeBytes) throws IOException {
            if (version == null || version.isEmpty() || version.length() > 64 || code <= 0 || schema == null
                    || schema.isEmpty() || schema.size() > MAX_PARAMETERS) throw invalid();
            this.version = version;
            this.code = code;
            Set<Long> ids = new HashSet<>();
            for (Parameter parameter : schema) {
                if (parameter == null || parameter.config <= 0 || parameter.config >= 0x100000
                        || parameter.index < 0 || parameter.index >= 0x4000 || parameter.type < 1 || parameter.type > 4
                        || parameter.nativeId <= 0 || !name(parameter.configName) || !name(parameter.name)
                        || !ids.add(parameter.nativeId) || parameters.put(key(parameter.config, parameter.index), parameter) != null) throw invalid();
                String previous = configs.put(parameter.config, parameter.configName);
                if (previous != null && !previous.equals(parameter.configName)) throw invalid();
            }
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                for (Parameter parameter : parameters.values()) {
                    JSONArray record = new JSONArray().put(parameter.config).put(parameter.index).put(parameter.configName)
                            .put(parameter.name).put(parameter.type).put(parameter.nativeId);
                    digest.update(record.toString().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\n');
                }
                StringBuilder identity = new StringBuilder();
                for (byte value : digest.digest()) identity.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                hash = identity.toString();
                JSONObject nativeValues = parse(nativeBytes);
                checkOverrides(nativeValues, this);
                overrides = nativeValues.toString();
            } catch (Exception failure) { throw invalid(); }
        }
    }

    /** Obtain only the current signed-in manager, its own file resolver and its typed records. */
    public static Snapshot capture(Activity activity) throws IOException {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) throw invalid();
        try {
            HookStatus.invoked(FamilyNames.DEVELOPER_OPTIONS);
            if (!HOST.equals(activity.getPackageName())) throw invalid();
            Object manager = DeveloperOptions.getOverrideStoreNative(activity);
            if (manager == null) throw invalid();
            File resolved = DeveloperOptions.getOverrideFileNative(manager);
            if (resolved == null || !resolved.isAbsolute()) throw invalid();
            File file = resolved.getCanonicalFile();
            String privateRoot = activity.getFilesDir().getCanonicalPath() + File.separator;
            if (!file.getPath().startsWith(privateRoot) || (file.exists() && !file.isFile())) throw invalid();
            List<?> nativeSchema = DeveloperOptions.getOverrideSchemaNative(manager);
            if (nativeSchema == null || nativeSchema.isEmpty() || nativeSchema.size() > MAX_PARAMETERS) throw invalid();
            List<Parameter> parameters = new ArrayList<>(nativeSchema.size());
            for (Object entry : nativeSchema) parameters.add(DeveloperOptions.getOverrideParameterNative(entry));
            PackageInfo host = activity.getPackageManager().getPackageInfo(HOST, 0);
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            if (file.exists()) {
                try (InputStream input = new FileInputStream(file)) { bytes = read(input); }
            }
            return new Snapshot(host.versionName, host.getLongVersionCode(), parameters, bytes);
        } catch (Throwable failure) {
            // Native exceptions can carry a session path or value. Never retain their causes.
            throw invalid();
        }
    }

    public static byte[] export(Snapshot snapshot) throws IOException {
        if (snapshot == null) throw invalid();
        try {
            JSONObject host = new JSONObject().put("package", HOST).put("version", snapshot.version).put("code", snapshot.code);
            JSONObject schema = new JSONObject().put("sha256", snapshot.hash).put("parameters", snapshot.parameters.size());
            byte[] file = new JSONObject().put("project", "HushGram-overrides").put("format", 1).put("host", host)
                    .put("schema", schema).put("overrides", new JSONObject(snapshot.overrides)).toString().getBytes(StandardCharsets.UTF_8);
            if (file.length > MAX_BYTES) throw invalid();
            return file;
        } catch (JSONException failure) { throw invalid(); }
    }

    /** Return only a count. Validation has no path to a native writer, file output or cache reload. */
    public static int validate(byte[] file, Snapshot snapshot) throws IOException {
        if (snapshot == null) throw invalid();
        try {
            JSONObject root = parse(file);
            if (root.length() != 5 || !"HushGram-overrides".equals(root.get("project")) || !(root.get("format") instanceof Integer)
                    || root.getInt("format") != 1 || !(root.get("host") instanceof JSONObject) || !(root.get("schema") instanceof JSONObject)
                    || !(root.get("overrides") instanceof JSONObject)) throw invalid();
            JSONObject host = root.getJSONObject("host"), schema = root.getJSONObject("schema");
            if (host.length() != 3 || !HOST.equals(host.get("package")) || !snapshot.version.equals(host.get("version"))
                    || !(host.get("code") instanceof Integer || host.get("code") instanceof Long) || host.getLong("code") != snapshot.code
                    || schema.length() != 2 || !snapshot.hash.equals(schema.get("sha256")) || !(schema.get("parameters") instanceof Integer)
                    || schema.getInt("parameters") != snapshot.parameters.size()) throw invalid();
            return checkOverrides(root.getJSONObject("overrides"), snapshot);
        } catch (JSONException | IllegalArgumentException failure) { throw invalid(); }
    }

    public static byte[] read(InputStream input) throws IOException {
        if (input == null) throw invalid();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (true) {
            int count = input.read(buffer, 0, Math.min(buffer.length, MAX_BYTES + 1 - bytes.size()));
            if (count == -1) return bytes.toByteArray();
            if (count <= 0) throw invalid();
            bytes.write(buffer, 0, count);
            if (bytes.size() > MAX_BYTES) throw invalid();
        }
    }

    private static JSONObject parse(byte[] bytes) throws IOException {
        try { return SettingsJson.parseObject(bytes, LIMITS); }
        catch (Exception failure) { throw invalid(); }
    }

    private static int checkOverrides(JSONObject values, Snapshot snapshot) throws IOException, JSONException {
        Set<Long> seen = new HashSet<>();
        for (java.util.Iterator<String> keys = values.keys(); keys.hasNext();) {
            String label = keys.next();
            int colon = label.indexOf(':');
            if (colon <= 0 || !label.substring(0, colon).matches("[1-9][0-9]{0,6}")) throw invalid();
            int config = Integer.parseInt(label.substring(0, colon));
            if (!label.substring(colon + 1).equals(snapshot.configs.get(config)) || !(values.get(label) instanceof JSONArray)) throw invalid();
            JSONArray parameters = values.getJSONArray(label);
            for (int i = 0; i < parameters.length(); i++) {
                if (!(parameters.get(i) instanceof String)) throw invalid();
                String record = parameters.getString(i);
                String[] parts = record.split(": ", 3);
                if (parts.length != 3 || record.indexOf('\0') >= 0 || !parts[0].matches("0|[1-9][0-9]{0,4}")) throw invalid();
                int index = Integer.parseInt(parts[0]);
                if (index >= 0x4000) throw invalid();
                Parameter parameter = snapshot.parameters.get(key(config, index));
                if (parameter == null || !parameter.name.equals(parts[1]) || !seen.add(key(config, index)) || seen.size() > MAX_OVERRIDES) throw invalid();
                String value = parts[2];
                if ("__NULL_VALUE__".equals(value)) continue;
                switch (parameter.type) {
                    case 1: if (!"true".equals(value) && !"false".equals(value)) throw invalid(); break;
                    case 2:
                        if (!value.matches("[+-]?[0-9]+")) throw invalid();
                        try { Long.parseLong(value); } catch (NumberFormatException failure) { throw invalid(); }
                        break;
                    case 3: break;
                    case 4:
                        if (!value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) throw invalid();
                        try { if (!Double.isFinite(Double.parseDouble(value))) throw invalid(); }
                        catch (NumberFormatException failure) { throw invalid(); }
                        break;
                    default: throw invalid();
                }
            }
        }
        return seen.size();
    }

    private static long key(int config, int index) { return ((long) config << 14) | index; }
    private static boolean name(String value) {
        return value != null && value.length() <= 256 && value.indexOf(':') < 0
                && value.chars().noneMatch(character -> character < 32 || character == 127);
    }
    private static IOException invalid() { return new IOException("Invalid or unavailable native overrides"); }
}
