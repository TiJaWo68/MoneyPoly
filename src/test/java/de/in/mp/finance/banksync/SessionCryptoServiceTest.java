package de.in.mp.finance.banksync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class SessionCryptoServiceTest {

    @Test
    void encryptsAndDecryptsWithUniqueIvUsingPassphraseKey() {
        SessionCryptoService crypto = new SessionCryptoService("super-secret-master-passphrase");
        assertThat(crypto.isConfigured()).isTrue();

        String plaintext = "{\"session_id\":\"abc-123\",\"refresh_token\":\"tok-xyz\"}";
        String encryptedFirst = crypto.encrypt(plaintext);
        String encryptedSecond = crypto.encrypt(plaintext);

        assertThat(encryptedFirst).isNotEqualTo(plaintext);
        assertThat(encryptedFirst).isNotEqualTo(encryptedSecond);
        assertThat(crypto.decrypt(encryptedFirst)).isEqualTo(plaintext);
        assertThat(crypto.decrypt(encryptedSecond)).isEqualTo(plaintext);
    }

    @Test
    void acceptsRaw32ByteBase64Key() {
        byte[] keyBytes = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        String base64Key = Base64.getEncoder().encodeToString(keyBytes);

        SessionCryptoService crypto = new SessionCryptoService(base64Key);
        String encrypted = crypto.encrypt("session-payload");

        assertThat(crypto.decrypt(encrypted)).isEqualTo("session-payload");
    }

    @Test
    void rejectsTamperedOrMalformedCiphertext() {
        SessionCryptoService crypto = new SessionCryptoService("master-key-1");
        String encrypted = crypto.encrypt("sensitive-session");
        String[] parts = encrypted.split(":");
        String tampered = parts[0] + ":AAAA" + parts[1].substring(4);

        assertThatThrownBy(() -> crypto.decrypt(tampered))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> crypto.decrypt("not-valid-format"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disablesEncryptionCleanlyWhenCryptoKeyMissing() {
        SessionCryptoService crypto = new SessionCryptoService("  ");

        assertThat(crypto.isConfigured()).isFalse();
        assertThatThrownBy(() -> crypto.encrypt("test"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.decrypt("abc:def"))
                .isInstanceOf(IllegalStateException.class);
    }
}
