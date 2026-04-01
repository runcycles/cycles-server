package io.runcycles.protocol.data.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

    private static String generateKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void encryptDecrypt_roundTrip() {
        CryptoService service = new CryptoService(generateKey());
        String secret = "whsec_test123";
        String encrypted = service.encrypt(secret);
        assertThat(encrypted).startsWith("enc:");
        assertThat(service.decrypt(encrypted)).isEqualTo(secret);
    }

    @Test
    void encrypt_differentIVs() {
        CryptoService service = new CryptoService(generateKey());
        assertThat(service.encrypt("s")).isNotEqualTo(service.encrypt("s"));
    }

    @Test
    void passThrough_noKey() {
        CryptoService service = new CryptoService("");
        assertThat(service.encrypt("plain")).isEqualTo("plain");
        assertThat(service.decrypt("plain")).isEqualTo("plain");
        assertThat(service.isEnabled()).isFalse();
    }

    @Test
    void nullHandling() {
        CryptoService service = new CryptoService(generateKey());
        assertThat(service.encrypt(null)).isNull();
        assertThat(service.decrypt(null)).isNull();
    }

    @Test
    void backwardCompat_plaintextDecrypt() {
        CryptoService service = new CryptoService(generateKey());
        assertThat(service.decrypt("whsec_old")).isEqualTo("whsec_old");
    }

    @Test
    void noKeyDecryptsEncryptedAsIs() {
        CryptoService noKey = new CryptoService("");
        assertThat(noKey.decrypt("enc:data")).isEqualTo("enc:data");
    }

    @Test
    void invalidKeyLength_throws() {
        assertThatThrownBy(() -> new CryptoService(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrongKey_throws() {
        CryptoService enc = new CryptoService(generateKey());
        CryptoService wrong = new CryptoService(generateKey());
        assertThatThrownBy(() -> wrong.decrypt(enc.encrypt("s"))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void isEnabled() {
        assertThat(new CryptoService(generateKey()).isEnabled()).isTrue();
    }
}
