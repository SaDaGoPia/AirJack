package com.ace4.airplayreceiver.raop

import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher

/**
 * RSA key exchange for legacy (RAOP / "classic AirPlay") sessions.
 *
 * The private key below is not this app's secret - it's the fixed 1024-bit RSA
 * keypair every RAOP receiver implementation (shairport, shairport-sync,
 * forked-daapd, iTunes-era AirPort Express clones, ...) has shared publicly for
 * ~15+ years, because every AirPlay *sender* (iOS, macOS, iTunes) encrypts the
 * per-session AES key against the matching fixed public key. Without embedding
 * this exact key, no RAOP receiver - open source or Apple's own hardware - can
 * decrypt the session key iOS sends. This copy is taken verbatim from
 * shairport-sync's common.c (`super_secret_key`), the reference implementation.
 */
object RaopCrypto {

    private const val PEM = "-----BEGIN RSA PRIVATE KEY-----\n" +
        "MIIEpQIBAAKCAQEA59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUt\n" +
        "wC5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDRKSKv6kDqnw4U\n" +
        "wPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuBOitnZ/bDzPHrTOZz0Dew0uowxf\n" +
        "/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJQ+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/\n" +
        "UAaHqn9JdsBWLUEpVviYnhimNVvYFZeCXg/IdTQ+x4IRdiXNv5hEewIDAQABAoIBAQDl8Axy9XfW\n" +
        "BLmkzkEiqoSwF0PsmVrPzH9KsnwLGH+QZlvjWd8SWYGN7u1507HvhF5N3drJoVU3O14nDY4TFQAa\n" +
        "LlJ9VM35AApXaLyY1ERrN7u9ALKd2LUwYhM7Km539O4yUFYikE2nIPscEsA5ltpxOgUGCY7b7ez5\n" +
        "NtD6nL1ZKauw7aNXmVAvmJTcuPxWmoktF3gDJKK2wxZuNGcJE0uFQEG4Z3BrWP7yoNuSK3dii2jm\n" +
        "lpPHr0O/KnPQtzI3eguhe0TwUem/eYSdyzMyVx/YpwkzwtYL3sR5k0o9rKQLtvLzfAqdBxBurciz\n" +
        "aaA/L0HIgAmOit1GJA2saMxTVPNhAoGBAPfgv1oeZxgxmotiCcMXFEQEWflzhWYTsXrhUIuz5jFu\n" +
        "a39GLS99ZEErhLdrwj8rDDViRVJ5skOp9zFvlYAHs0xh92ji1E7V/ysnKBfsMrPkk5KSKPrnjndM\n" +
        "oPdevWnVkgJ5jxFuNgxkOLMuG9i53B4yMvDTCRiIPMQ++N2iLDaRAoGBAO9v//mU8eVkQaoANf0Z\n" +
        "oMjW8CN4xwWA2cSEIHkd9AfFkftuv8oyLDCG3ZAf0vrhrrtkrfa7ef+AUb69DNggq4mHQAYBp7L+\n" +
        "k5DKzJrKuO0r+R0YbY9pZD1+/g9dVt91d6LQNepUE/yY2PP5CNoFmjedpLHMOPFdVgqDzDFxU8hL\n" +
        "AoGBANDrr7xAJbqBjHVwIzQ4To9pb4BNeqDndk5Qe7fT3+/H1njGaC0/rXE0Qb7q5ySgnsCb3DvA\n" +
        "cJyRM9SJ7OKlGt0FMSdJD5KG0XPIpAVNwgpXXH5MDJg09KHeh0kXo+QA6viFBi21y340NonnEfdf\n" +
        "54PX4ZGS/Xac1UK+pLkBB+zRAoGAf0AY3H3qKS2lMEI4bzEFoHeK3G895pDaK3TFBVmD7fV0Zhov\n" +
        "17fegFPMwOII8MisYm9ZfT2Z0s5Ro3s5rkt+nvLAdfC/PYPKzTLalpGSwomSNYJcB9HNMlmhkGzc\n" +
        "1JnLYT4iyUyx6pcZBmCd8bD0iwY/FzcgNDaUmbX9+XDvRA0CgYEAkE7pIPlE71qvfJQgoA9em0gI\n" +
        "LAuE4Pu13aKiJnfft7hIjbK+5kyb3TysZvoyDnb3HOKvInK7vXbKuU4ISgxB2bB3HcYzQMGsz1qJ\n" +
        "2gG0N5hvJpzwwhbhXqFKA4zaaSrw622wDniAK5MlIE0tIAKKP4yxNGjoD2QYjhBGuhvkWKY=\n" +
        "-----END RSA PRIVATE KEY-----\n"

    private val privateKey: RSAPrivateKey by lazy { loadPrivateKey() }

    /** Decrypts the RSA-OAEP(SHA-1)-wrapped AES session key from an ANNOUNCE's a=rsaaeskey. */
    fun decryptAesKey(rsaEncryptedKey: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        return cipher.doFinal(rsaEncryptedKey)
    }

    /**
     * Produces the Apple-Response for an Apple-Challenge header: a raw RSA
     * private-key transform (PKCS#1 v1.5 padding, no digest prefix) over
     * challenge || serverIp || deviceId, zero-padded to at least 32 bytes.
     * Using Cipher.ENCRYPT_MODE with a private key performs this "sign"
     * primitive directly - Java's Signature API can't do a raw, un-hashed
     * PKCS#1 transform, which is what this legacy handshake requires.
     */
    fun signAppleChallenge(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        return cipher.doFinal(data)
    }

    private fun loadPrivateKey(): RSAPrivateKey {
        val base64 = PEM
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\n", "")
        val pkcs1Bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        val pkcs8Bytes = pkcs1ToPkcs8(pkcs1Bytes)
        val keySpec = PKCS8EncodedKeySpec(pkcs8Bytes)
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec) as RSAPrivateKey
    }

    /**
     * Wraps a traditional PKCS#1 RSA private key DER blob (as found in
     * "-----BEGIN RSA PRIVATE KEY-----" PEM files) in the fixed PKCS#8
     * ASN.1 header Java's KeyFactory actually accepts. Valid for any DER
     * body under 64KB (true for any RSA key size in practical use), since
     * it hardcodes 2-byte DER length fields.
     */
    private fun pkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
        val totalLength = pkcs1.size + 22
        val header = byteArrayOf(
            0x30, 0x82.toByte(), (totalLength shr 8).toByte(), totalLength.toByte(),
            0x02, 0x01, 0x00,
            0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(),
            0x0D, 0x01, 0x01, 0x01, 0x05, 0x00,
            0x04, 0x82.toByte(), (pkcs1.size shr 8).toByte(), pkcs1.size.toByte()
        )
        return header + pkcs1
    }
}
