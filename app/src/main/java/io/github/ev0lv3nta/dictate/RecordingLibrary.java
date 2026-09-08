package io.github.ev0lv3nta.dictate;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Последние записи, лежащие на устройстве.
 *
 * Нужна в двух случаях. Первый: распознавание не дошло до сети — отвалился
 * VPN, кончился баланс, упал провайдер, — и запись можно прогнать заново,
 * не переговаривая мысль. Второй: диктофон внутри приложения, где запись
 * делают руками и потом сравнивают модели на одном и том же звуке.
 *
 * Сам звук — сырой PCM во внутреннем каталоге приложения, метаданные и
 * распознанный текст — в отдельном файле настроек. Наружу ничего не уходит,
 * в логи пишется только идентификатор.
 */
final class RecordingLibrary {

    private static final String TAG = "DictateRecording";
    private static final String PREFS_NAME = "dictate_recordings";
    private static final String KEY_INDEX = "index";
    private static final String DIRECTORY = "recordings";
    private static final String LEGACY_NAME = "last_recording.pcm";

    /** Сколько записей держим: одиннадцатая вытесняет самую старую. */
    static final int MAX_ENTRIES = 10;
    /** 300 с при 16 kHz PCM16 — потолок одной записи, который допускают настройки. */
    private static final long MAX_BYTES = 12L * 1024 * 1024;
    /** Потолок каталога: внутренняя память телефона не бездонная. */
    private static final long MAX_TOTAL_BYTES = 72L * 1024 * 1024;

    static final String SOURCE_KEYBOARD = "keyboard";
    static final String SOURCE_APP = "app";

    /** Один процесс, но два потока: сервис распознавания и экран диктофона. */
    private static final Object LOCK = new Object();

    static final class Entry {
        final String id;
        final long createdAt;
        final long durationMillis;
        final long sizeBytes;
        final String source;
        /** Распознанный текст последней попытки, если она была. */
        final String text;
        final String textProvider;
        final String textModel;

        Entry(String id, long createdAt, long durationMillis, long sizeBytes, String source,
              String text, String textProvider, String textModel) {
            this.id = id;
            this.createdAt = createdAt;
            this.durationMillis = durationMillis;
            this.sizeBytes = sizeBytes;
            this.source = source;
            this.text = text;
            this.textProvider = textProvider;
            this.textModel = textModel;
        }

        boolean hasText() {
            return text != null && !text.isEmpty();
        }

        boolean fromKeyboard() {
            return SOURCE_KEYBOARD.equals(source);
        }

        private JSONObject toJson() throws JSONException {
            JSONObject object = new JSONObject();
            object.put("id", id);
            object.put("createdAt", createdAt);
            object.put("durationMillis", durationMillis);
            object.put("sizeBytes", sizeBytes);
            object.put("source", source);
            if (text != null) {
                object.put("text", text);
                object.put("textProvider", textProvider);
                object.put("textModel", textModel);
            }
            return object;
        }

        private static Entry fromJson(JSONObject object) {
            String id = object.optString("id", "");
            if (id.isEmpty()) {
                return null;
            }
            String text = object.has("text") ? object.optString("text", null) : null;
            return new Entry(id,
                    object.optLong("createdAt"),
                    object.optLong("durationMillis"),
                    object.optLong("sizeBytes"),
                    object.optString("source", SOURCE_KEYBOARD),
                    text,
                    object.optString("textProvider", ""),
                    object.optString("textModel", ""));
        }
    }

    private final SharedPreferences preferences;
    private final File directory;
    private final File legacy;
    private final AtomicFile indexFile;

    RecordingLibrary(Context context) {
        this(context, null);
    }

    RecordingLibrary(Context context, AtomicFile indexOverride) {
        Context application = context.getApplicationContext();
        preferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        directory = new File(application.getFilesDir(), DIRECTORY);
        legacy = new File(application.getFilesDir(), LEGACY_NAME);
        indexFile = indexOverride != null ? indexOverride
                : new AtomicFile(new File(application.getFilesDir(), "recordings-index.json"));
        importLegacy();
        synchronized (LOCK) { recover(); }
    }

    /** Записи от новой к старой. */
    List<Entry> list() {
        synchronized (LOCK) {
            return read();
        }
    }

    int count() {
        return list().size();
    }

    Entry newest() {
        List<Entry> entries = list();
        return entries.isEmpty() ? null : entries.get(0);
    }

    Entry entry(String id) {
        for (Entry entry : list()) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Кладём запись в каталог. Файл пишется через временный: прерванная
     * запись не оставит битый огрызок, на который потом наткнётся плеер.
     */
    Entry add(byte[] pcm, String source) {
        if (pcm == null || pcm.length == 0 || pcm.length > MAX_BYTES) {
            return null;
        }
        synchronized (LOCK) {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                Log.w(TAG, "не удалось создать каталог записей");
                return null;
            }
            long createdAt = System.currentTimeMillis();
            String id = Long.toString(createdAt);
            List<Entry> entries = read();
            int suffix = 1;
            while (contains(entries, id)) {
                id = createdAt + "-" + suffix++;
            }

            File target = file(id);
            File temp = new File(directory, id + ".tmp");
            try (OutputStream output = new FileOutputStream(temp)) {
                output.write(pcm);
                output.flush();
            } catch (IOException error) {
                Log.w(TAG, "не удалось сохранить запись: " + error.getClass().getSimpleName());
                temp.delete();
                return null;
            }
            if (!temp.renameTo(target)) {
                Log.w(TAG, "не удалось переместить запись на место");
                temp.delete();
                return null;
            }

            Entry entry = new Entry(id, createdAt,
                    pcm.length * 1000L / (AudioCapture.SAMPLE_RATE * 2L),
                    pcm.length, source, null, null, null);
            entries.add(0, entry);
            if (!writeWithQuota(entries)) { target.delete(); return null; }
            return entry;
        }
    }

    /** Текст последней попытки распознавания вместе с моделью, которая его дала. */
    void setText(String id, String text, String provider, String model) {
        synchronized (LOCK) {
            List<Entry> entries = read();
            for (int index = 0; index < entries.size(); index++) {
                Entry entry = entries.get(index);
                if (entry.id.equals(id)) {
                    entries.set(index, new Entry(entry.id, entry.createdAt, entry.durationMillis,
                            entry.sizeBytes, entry.source, text, provider, model));
                    write(entries);
                    return;
                }
            }
        }
    }

    byte[] load(String id) throws IOException {
        File source = file(id);
        long length = source.length();
        if (length <= 0 || length > MAX_BYTES) {
            throw new IOException("Сохранённая запись недоступна");
        }
        byte[] pcm = new byte[(int) length];
        try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
            input.readFully(pcm);
        }
        return pcm;
    }

    void delete(String id) {
        synchronized (LOCK) {
            List<Entry> entries = read();
            List<Entry> kept = new ArrayList<>(entries.size());
            for (Entry entry : entries) {
                if (entry.id.equals(id)) {
                    file(entry.id).delete();
                } else {
                    kept.add(entry);
                }
            }
            write(kept);
        }
    }

    void clear() {
        synchronized (LOCK) {
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) if (file.isFile()) file.delete();
            legacy.delete();
            preferences.edit().clear().commit();
            write(Collections.<Entry>emptyList());
        }
    }

    private File file(String id) {
        if (!id.matches("(?:legacy-)?[0-9]+(?:-[0-9]+)?")) throw new IllegalArgumentException("Invalid recording ID");
        return new File(directory, id + ".pcm");
    }

    private static boolean contains(List<Entry> entries, String id) {
        for (Entry entry : entries) {
            if (entry.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Select the retained index first; never delete old audio before its commit. */
    private List<Entry> evict(List<Entry> entries) {
        List<Entry> kept = new ArrayList<>(entries.size());
        long total = 0L;
        for (Entry entry : entries) {
            long actualSize = file(entry.id).length();
            boolean overflow = kept.size() >= MAX_ENTRIES || actualSize > MAX_BYTES
                    || total + actualSize > MAX_TOTAL_BYTES;
            if (!overflow) {
                kept.add(entry);
                total += actualSize;
            }
        }
        return kept;
    }

    private boolean writeWithQuota(List<Entry> entries) {
        List<Entry> kept=evict(entries);
        if (!write(kept)) return false;
        for (Entry entry:entries) if (!contains(kept,entry.id)) file(entry.id).delete();
        return true;
    }

    private List<Entry> read() {
        List<Entry> entries = new ArrayList<>();
        String raw = preferences.getString(KEY_INDEX, "");
        if (indexFile.getBaseFile().exists()) {
            try { raw = new String(indexFile.readFully(), java.nio.charset.StandardCharsets.UTF_8); }
            catch (IOException error) { raw = ""; }
        }
        if (raw == null || raw.isEmpty()) {
            return entries;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                JSONObject object = array.optJSONObject(index);
                if (object == null) {
                    continue;
                }
                Entry entry = Entry.fromJson(object);
                // Файл могли вычистить снаружи — в индексе такой записи не место.
                if (entry != null && entry.id.matches("(?:legacy-)?[0-9]+(?:-[0-9]+)?")
                        && file(entry.id).isFile()) {
                    entries.add(entry);
                }
            }
        } catch (JSONException error) {
            Log.w(TAG, "индекс записей повреждён, начинаем заново");
            return new ArrayList<>();
        }
        return entries;
    }

    private boolean write(List<Entry> entries) {
        JSONArray array = new JSONArray();
        try {
            for (Entry entry : entries) {
                array.put(entry.toJson());
            }
        } catch (JSONException error) {
            Log.w(TAG, "не удалось собрать индекс записей");
            return false;
        }
        FileOutputStream output = null;
        try {
            output = indexFile.startWrite();
            output.write(array.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            indexFile.finishWrite(output);
            preferences.edit().remove(KEY_INDEX).commit();
            return true;
        } catch (IOException failure) {
            if (output != null) indexFile.failWrite(output);
            return false;
        }
    }

    private void recover() {
        List<Entry> entries = read();
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".tmp")) { file.delete(); continue; }
            if (name.matches("(?:legacy-)?[0-9]+(?:-[0-9]+)?[.]pcm")) {
                String id = name.substring(0,name.length()-4);
                long size=file.length();
                if (!contains(entries,id) && size>0 && size<=MAX_BYTES && size%2==0)
                    entries.add(new Entry(id,file.lastModified(),size*1000L/(AudioCapture.SAMPLE_RATE*2),
                            size,SOURCE_APP,null,null,null));
            }
        }
        entries.sort((a,b) -> Long.compare(b.createdAt,a.createdAt));
        writeWithQuota(entries);
    }

    /** Единственная запись из прежней версии приложения переезжает в каталог. */
    private void importLegacy() {
        synchronized (LOCK) {
            if (!legacy.isFile() || legacy.length() <= 0 || legacy.length() > MAX_BYTES
                    || legacy.length() % 2 != 0) {
                return;
            }
            if (!directory.isDirectory() && !directory.mkdirs()) {
                return;
            }
            long createdAt = legacy.lastModified();
            String id = "legacy-" + createdAt;
            List<Entry> entries = read();
            if (!contains(entries, id)) {
                try { java.nio.file.Files.copy(legacy.toPath(), file(id).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                catch (IOException failed) { return; }
                long size = file(id).length();
                entries.add(0, new Entry(id, createdAt,
                        size * 1000L / (AudioCapture.SAMPLE_RATE * 2L), size,
                        SOURCE_KEYBOARD, null, null, null));
                if (writeWithQuota(entries)) legacy.delete();
            }
        }
    }
}
