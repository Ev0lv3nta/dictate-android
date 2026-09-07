package io.github.ev0lv3nta.dictate;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class StorageTest {
    private Context context;
    @Before public void setup() {
        assertEquals("integration", BuildConfig.BUILD_TYPE);
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }
    @Test public void encryptedKeySurvivesReopenAndCorruptionIsDistinct() throws Exception {
        SecureApiKeyStore keys = new SecureApiKeyStore(context);
        String key = "invalid-test-credential-for-contract";
        keys.save("elevenlabs", key);
        assertEquals(key, new SecureApiKeyStore(context).load("elevenlabs"));
        String persistence = context.getSharedPreferences("dictate_secrets",0).getAll().toString();
        assertFalse(persistence.contains(key));
        context.getSharedPreferences("dictate_secrets",0).edit().putString("api_key_iv","malformed").commit();
        assertTrue(keys.isUnreadable("elevenlabs"));
        keys.clear("elevenlabs");
        assertNull(keys.load("elevenlabs"));
        assertFalse(keys.isUnreadable("elevenlabs"));
    }
    @Test public void historyDeletionDoesNotResurrectAndQuotaIsBounded() throws Exception {
        RecordingLibrary library = new RecordingLibrary(context);
        library.clear();
        byte[] pcm = new byte[16000];
        for (int i=0;i<12;i++) assertNotNull(library.add(pcm,RecordingLibrary.SOURCE_APP));
        assertEquals(10,library.count());
        String id=library.newest().id;
        library.delete(id);
        library.setText(id,"fixture", "elevenlabs", "scribe_v2");
        assertNull(library.entry(id));
        library.clear();
        assertEquals(0,library.count());
        File[] files = new File(context.getFilesDir(),"recordings").listFiles();
        assertTrue(files == null || files.length == 0);
    }
    @Test public void corruptIndexRecoversAudioAndRejectsTraversal() throws Exception {
        RecordingLibrary library = new RecordingLibrary(context);
        library.clear();
        RecordingLibrary.Entry saved=library.add(new byte[16000],RecordingLibrary.SOURCE_APP);
        Files.write(new File(context.getFilesDir(),"recordings-index.json").toPath(), "broken".getBytes(StandardCharsets.UTF_8));
        RecordingLibrary recovered=new RecordingLibrary(context);
        assertNotNull(recovered.entry(saved.id));
        try { recovered.load("../../secret"); fail(); }
        catch (IllegalArgumentException expected) { }
        recovered.clear();
    }
    @Test public void failedIndexCommitDoesNotEvictExistingAudio() throws Exception {
        RecordingLibrary library=new RecordingLibrary(context);
        library.clear();
        byte[] pcm=new byte[16000];
        String oldest=library.add(pcm,RecordingLibrary.SOURCE_APP).id;
        for (int i=1;i<10;i++) library.add(pcm,RecordingLibrary.SOURCE_APP);
        android.util.AtomicFile failedIndex=new android.util.AtomicFile(
                new File(context.getFilesDir(),"recordings-index.json")) {
            @Override public java.io.FileOutputStream startWrite() throws java.io.IOException {
                throw new java.io.IOException("Simulated full storage");
            }
        };
        RecordingLibrary failing=new RecordingLibrary(context,failedIndex);
        assertNull(failing.add(pcm,RecordingLibrary.SOURCE_APP));
        assertArrayEquals(pcm,library.load(oldest));
        assertEquals(10,library.count());
        library.clear();
    }
    @Test public void legacyAudioMigratesOnlyAfterCommittedIndex() throws Exception {
        RecordingLibrary library=new RecordingLibrary(context);
        library.clear();
        File old=new File(context.getFilesDir(),"last_recording.pcm");
        byte[] pcm=new byte[16000]; pcm[200]=17;
        Files.write(old.toPath(),pcm);
        RecordingLibrary migrated=new RecordingLibrary(context);
        assertFalse(old.exists());
        assertEquals(1,migrated.count());
        assertArrayEquals(pcm,migrated.load(migrated.newest().id));
        migrated.clear();
    }
    @Test public void historyStartsDisabled() {
        AppPreferences preferences=new AppPreferences(context);
        assertFalse(preferences.isHistoryEnabled());
    }
}
