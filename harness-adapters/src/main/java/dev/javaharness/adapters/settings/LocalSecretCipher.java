package dev.javaharness.adapters.settings;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;

/** AES-GCM credentials in the database; the installation key is stored separately on local disk. */
public final class LocalSecretCipher {
    private final Path keyFile;
    private final SecureRandom random = new SecureRandom();
    public LocalSecretCipher(Path keyFile) { this.keyFile = keyFile.toAbsolutePath().normalize(); }

    private synchronized byte[] key(boolean create) throws Exception {
        if (!Files.exists(keyFile) && create) {
            Files.createDirectories(keyFile.getParent());
            byte[] bytes = new byte[32]; random.nextBytes(bytes);
            FileAttribute<?>[] attrs = Files.getFileStore(keyFile.getParent()).supportsFileAttributeView("posix")
                    ? new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")) }
                    : new FileAttribute<?>[0];
            try (var channel = Files.newByteChannel(keyFile, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attrs)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
            } catch (FileAlreadyExistsException anotherInstance) { /* Read the existing installation key. */ }
        }
        if (Files.size(keyFile) != 32) throw new IllegalStateException("Invalid installation key");
        return Files.readAllBytes(keyFile);
    }

    public String encrypt(String value) {
        if (value.isEmpty()) return "";
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(true), "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (Exception error) { throw new IllegalStateException("无法保存 API Key，请检查本地配置密钥文件"); }
    }

    public String decrypt(String value) {
        if (value.isEmpty()) return "";
        try {
            if (!value.startsWith("v1:")) throw new IllegalArgumentException();
            byte[] data = Base64.getDecoder().decode(value.substring(3));
            if (data.length < 28) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(false), "AES"), new GCMParameterSpec(128, Arrays.copyOfRange(data, 0, 12)));
            return new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalStateException("无法读取 API Key，请恢复与数据库配套的配置密钥文件"); }
    }
}
