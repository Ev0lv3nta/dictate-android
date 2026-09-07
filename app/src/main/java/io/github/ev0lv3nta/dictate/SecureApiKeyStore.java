package io.github.ev0lv3nta.dictate;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Ключи провайдеров шифруются ключом из Android Keystore. */
final class SecureApiKeyStore {

    private static final String TAG = "DictateKeyStore";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    /** Имя из первой версии: это просто AES-ключ, переименование стёрло бы сохранённое. */
    private static final String KEY_ALIAS = "dictate_elevenlabs_api_key_v1";
    private static final String PREFS = "dictate_secrets";
    private static final String LEGACY_CIPHERTEXT = "api_key_ciphertext";
    private static final String LEGACY_IV = "api_key_iv";

    private final SharedPreferences preferences;

    SecureApiKeyStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized void save(String providerId, String apiKey) throws Exception {
        String normalized = apiKey == null ? "" : apiKey.trim();
        if (normalized.length() < 20 || normalized.length() > 512
                || normalized.matches(".*\\s+.*")) {
            throw new IllegalArgumentException("Ключ выглядит некорректно");
        }

        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] ciphertext = cipher.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
        boolean stored = preferences.edit()
                .putString(ciphertextName(providerId),
                        Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(ivName(providerId),
                        Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .commit();
        if (!stored) {
            throw new IOException("Не удалось записать зашифрованный ключ");
        }
    }

    /** Возвращает сохранённый ключ или {@code null}, если ключ не настроен. */
    String load(String providerId) {
        return loadCustom(providerId);
    }

    synchronized String loadCustom(String providerId) {
        String ciphertext = preferences.getString(ciphertextName(providerId), null);
        String iv = preferences.getString(ivName(providerId), null);
        if (ciphertext == null || iv == null) {
            return null;
        }
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            SecretKey key = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
            if (key == null) {
                return null;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            byte[] plaintext = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP));
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception error) {
            // Never log the ciphertext, plaintext or exception details from the keystore.
            Log.e(TAG, "Не удалось расшифровать сохранённый ключ");
            return null;
        }
    }

    boolean hasCustomKey(String providerId) {
        String value = loadCustom(providerId);
        return value != null && !value.isEmpty();
    }

    boolean isUnreadable(String providerId) {
        return (preferences.contains(ciphertextName(providerId)) || preferences.contains(ivName(providerId)))
                && loadCustom(providerId) == null;
    }

    /** Показывает вид ключа, не раскрывая сам ключ: sk_c2fa…64bc. */
    static String mask(String apiKey) {
        if (apiKey == null || apiKey.length() < 12) {
            return "—";
        }
        return "••••" + apiKey.substring(apiKey.length() - 4);
    }

    synchronized void clear(String providerId) {
        boolean cleared = preferences.edit()
                .remove(ciphertextName(providerId))
                .remove(ivName(providerId))
                .commit();
        if (!cleared) throw new IllegalStateException("Не удалось удалить ключ");
    }

    private static String ciphertextName(String providerId) {
        return ModelCatalog.PROVIDER_ELEVENLABS.equals(providerId)
                ? LEGACY_CIPHERTEXT : providerId + "_ciphertext";
    }

    private static String ivName(String providerId) {
        return ModelCatalog.PROVIDER_ELEVENLABS.equals(providerId)
                ? LEGACY_IV : providerId + "_iv";
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);
        SecretKey existing = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (existing != null) {
            return existing;
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }
}
