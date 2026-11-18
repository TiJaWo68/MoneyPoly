package de.in.mp.finance.banksync;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Encrypts and decrypts Open-Banking session payloads using AES-256-GCM.
 * <p>
 * The master key is supplied via {@code MONEYPOLY_CRYPTO_KEY} (or
 * {@code moneypoly.banksync.crypto-key}). If unconfigured, the application
 * still starts cleanly and {@link #isConfigured()} returns {@code false}.
 */
@Service
public class SessionCryptoService {

    private static final String AES_ALGORITHM = "AES";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String PAYLOAD_SEPARATOR = ":";

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public SessionCryptoService(
            @Value("${moneypoly.banksync.crypto-key:${MONEYPOLY_CRYPTO_KEY:}}") String rawKey) {
        this.secretKey = deriveKey(rawKey);
    }

    public boolean isConfigured() {
        return secretKey != null;
    }

    public String encrypt(String plaintext) {
        if (!isConfigured()) {
            throw new IllegalStateException("MONEYPOLY_CRYPTO_KEY is not configured");
        }
        if (plaintext == null) {
            return null;
        }
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv)
                    + PAYLOAD_SEPARATOR
                    + Base64.getEncoder().encodeToString(cipherText);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to encrypt Open-Banking session payload", ex);
        }
    }

    public String decrypt(String encryptedPayload) {
        if (!isConfigured()) {
            throw new IllegalStateException("MONEYPOLY_CRYPTO_KEY is not configured");
        }
        if (encryptedPayload == null || encryptedPayload.isBlank()) {
            return null;
        }
        String[] parts = encryptedPayload.split(PAYLOAD_SEPARATOR, -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Malformed encrypted Open-Banking session payload");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] cipherText = Base64.getDecoder().decode(parts[1]);
            if (iv.length != IV_BYTES) {
                throw new IllegalArgumentException("Invalid AES-GCM IV length");
            }
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plainBytes = cipher.doFinal(cipherText);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("Failed to decrypt Open-Banking session payload", ex);
        }
    }

    private static SecretKey deriveKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        String trimmed = rawKey.trim();
        try {
            byte[] decoded = Base64.getDecoder().decode(trimmed);
            if (decoded.length == KEY_BYTES) {
                return new SecretKeySpec(decoded, AES_ALGORITHM);
            }
        } catch (IllegalArgumentException ignored) {
            // Fall back to SHA-256 derivation for passphrase keys.
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(trimmed.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, AES_ALGORITHM);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
