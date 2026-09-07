package io.github.ev0lv3nta.dictate;

final class BackendSelection {
    static void requireKey(String key, String provider) throws Transcription.ApiException {
        Transcription.requireKey(key, provider);
    }
    static Transcription.Client client(String provider) { return Transcription.clientFor(provider); }
}
