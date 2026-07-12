package com.iskeru.computeadmin.ssh;

import jakarta.annotation.PostConstruct;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

/**
 * Owns the single app-owned SSH keypair (S2). Generates an <strong>ed25519</strong>
 * pair on first boot at {@code ca.ssh.key-path} (default {@code ./data/id_ed25519},
 * {@code chmod 600}) if absent, reuses it thereafter, and exposes the public key
 * in OpenSSH form (to install on targets) plus its SHA-256 fingerprint. The
 * {@link KeyPair} authenticates every {@link MinaSshExecutor} connection.
 *
 * <p>The private key is stored unencrypted; filesystem permissions are the only
 * boundary for now (S2).
 *
 * <p>The keypair is generated and loaded through MINA's
 * {@link SecurityUtils#getKeyPairGenerator(String) SecurityUtils} EdDSA factories
 * (not the JDK's native {@code Ed25519} provider): MINA signs the public-key auth
 * challenge with its own EdDSA provider, and JDK-native {@code EdECKey} objects do
 * not interoperate with it — so a JDK-generated key makes every target reject auth
 * and show UNREACHABLE. The on-disk format is unchanged (base64 PKCS#8 private key
 * plus the {@code .pub} OpenSSH line), so keys round-trip across boots either way.
 *
 * <p>spec-003.
 */
@Service
public class KeyService {

    private static final String OPENSSH_TYPE = "ssh-ed25519";
    /** Raw ed25519 public key length; also the SPKI suffix we slice off. */
    private static final int RAW_PUBLIC_KEY_LENGTH = 32;

    private final Path privateKeyPath;
    private final Path publicKeyPath;

    private KeyPair keyPair;
    private String publicKeyOpenSsh;
    private String fingerprint;

    public KeyService(@Value("${ca.ssh.key-path:./data/id_ed25519}") String keyPath) {
        this.privateKeyPath = Path.of(keyPath);
        this.publicKeyPath = Path.of(keyPath + ".pub");
    }

    @PostConstruct
    void init() {
        this.keyPair = Files.exists(privateKeyPath) ? load() : generateAndStore();
        byte[] blob = openSshBlob(keyPair.getPublic());
        // Trailing comment is the optional third authorized_keys field: it labels the
        // key on the target (identifies it as this app's) and is ignored by SSH auth.
        this.publicKeyOpenSsh = OPENSSH_TYPE + " " + Base64.getEncoder().encodeToString(blob) + " compute-admin";
        this.fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(sha256(blob));
    }

    /** The keypair used to authenticate to targets. */
    public KeyPair keyPair() {
        return keyPair;
    }

    /** The public key in {@code ssh-ed25519 AAAA...} form, ready for authorized_keys. */
    public String publicKeyOpenSsh() {
        return publicKeyOpenSsh;
    }

    /** The public key's {@code SHA256:...} fingerprint. */
    public String fingerprint() {
        return fingerprint;
    }

    private KeyPair generateAndStore() {
        try {
            KeyPair generated = SecurityUtils.getKeyPairGenerator(SecurityUtils.EDDSA).generateKeyPair();
            Path parent = privateKeyPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(privateKeyPath, Base64.getEncoder().encode(generated.getPrivate().getEncoded()));
            Files.write(publicKeyPath, (publicKeyLine(generated.getPublic())).getBytes(StandardCharsets.UTF_8));
            restrictToOwner(privateKeyPath);
            return generated;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Failed to generate the app SSH keypair", e);
        }
    }

    private KeyPair load() {
        try {
            byte[] pkcs8 = Base64.getDecoder().decode(Files.readAllBytes(privateKeyPath));
            KeyFactory factory = SecurityUtils.getKeyFactory(SecurityUtils.EDDSA);
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            PublicKey publicKey = derivePublicKey(factory);
            return new KeyPair(publicKey, privateKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to load the app SSH keypair", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the app SSH key at " + privateKeyPath, e);
        }
    }

    /** Reads the stored X.509 public key written beside the private key. */
    private PublicKey derivePublicKey(KeyFactory factory) throws GeneralSecurityException {
        try {
            String line = Files.readString(publicKeyPath, StandardCharsets.UTF_8).trim();
            String base64 = line.substring(line.indexOf(' ') + 1);
            byte[] blob = Base64.getDecoder().decode(base64);
            byte[] raw = Arrays.copyOfRange(blob, blob.length - RAW_PUBLIC_KEY_LENGTH, blob.length);
            return factory.generatePublic(new X509EncodedKeySpec(x509FromRaw(raw)));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the app SSH public key at " + publicKeyPath, e);
        }
    }

    private String publicKeyLine(PublicKey publicKey) {
        return OPENSSH_TYPE + " " + Base64.getEncoder().encodeToString(openSshBlob(publicKey));
    }

    /** OpenSSH wire encoding: {@code string "ssh-ed25519"} then {@code string <32-byte key>}. */
    private static byte[] openSshBlob(PublicKey publicKey) {
        byte[] raw = rawPublicKey(publicKey);
        byte[] type = OPENSSH_TYPE.getBytes(StandardCharsets.US_ASCII);
        byte[] blob = new byte[4 + type.length + 4 + raw.length];
        int offset = writeLengthPrefixed(blob, 0, type);
        writeLengthPrefixed(blob, offset, raw);
        return blob;
    }

    private static int writeLengthPrefixed(byte[] dest, int offset, byte[] value) {
        dest[offset] = (byte) (value.length >>> 24);
        dest[offset + 1] = (byte) (value.length >>> 16);
        dest[offset + 2] = (byte) (value.length >>> 8);
        dest[offset + 3] = (byte) value.length;
        System.arraycopy(value, 0, dest, offset + 4, value.length);
        return offset + 4 + value.length;
    }

    /** The raw 32-byte ed25519 public key: the tail of its X.509 SubjectPublicKeyInfo. */
    private static byte[] rawPublicKey(PublicKey publicKey) {
        byte[] spki = publicKey.getEncoded();
        return Arrays.copyOfRange(spki, spki.length - RAW_PUBLIC_KEY_LENGTH, spki.length);
    }

    /** Rebuilds the 44-byte ed25519 X.509 SubjectPublicKeyInfo around a raw key. */
    private static byte[] x509FromRaw(byte[] raw) {
        byte[] prefix = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
        byte[] spki = new byte[prefix.length + raw.length];
        System.arraycopy(prefix, 0, spki, 0, prefix.length);
        System.arraycopy(raw, 0, spki, prefix.length, raw.length);
        return spki;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void restrictToOwner(Path path) {
        try {
            Set<PosixFilePermission> ownerOnlyPerms = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(path, ownerOnlyPerms);
        } catch (UnsupportedOperationException | IOException e) {
            // Non-POSIX filesystem (e.g. Windows/CI): best-effort, perms are the S2 boundary.
        }
    }
}
