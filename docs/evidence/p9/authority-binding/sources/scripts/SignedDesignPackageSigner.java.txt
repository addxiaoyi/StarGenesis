import dev.stargenesis.api.design.DesignDocument;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.SignedDesignCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.regex.Pattern;

/** Creates and verifies Ed25519 signed design packages using the plugin's strict schema codec. */
public final class SignedDesignPackageSigner {
    private static final int MAX_KEY_BYTES = 4096;
    private static final Pattern KEY_ID = Pattern.compile("[a-z0-9][a-z0-9_.:-]{0,63}");
    private final SignedDesignCodec signed = new SignedDesignCodec(new DesignCodec());

    public static void main(String[] args) {
        try {
            if (args.length == 0) throw new IllegalArgumentException(usage());
            switch (args[0]) {
                case "keygen" -> keygen(args);
                case "sign" -> new SignedDesignPackageSigner().sign(args);
                case "verify" -> new SignedDesignPackageSigner().verify(args);
                default -> throw new IllegalArgumentException(usage());
            }
        } catch (IOException | GeneralSecurityException | IllegalArgumentException failure) {
            System.err.println("Signed design operation failed: " + failure.getMessage());
            System.exit(1);
        }
    }

    private static void keygen(String[] args) throws GeneralSecurityException, IOException {
        if (args.length != 3) throw new IllegalArgumentException("Usage: keygen <private.pem> <public.pem>");
        Path privateFile = Path.of(args[1]).toAbsolutePath().normalize();
        Path publicFile = Path.of(args[2]).toAbsolutePath().normalize();
        if (privateFile.equals(publicFile) || Files.exists(privateFile) || Files.exists(publicFile))
            throw new IOException("Key output files must be distinct and must not already exist");
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        writeNew(privateFile, pem("PRIVATE KEY", pair.getPrivate().getEncoded()).getBytes(StandardCharsets.US_ASCII), true);
        try { writeNew(publicFile, pem("PUBLIC KEY", pair.getPublic().getEncoded()).getBytes(StandardCharsets.US_ASCII), false); }
        catch (IOException failure) { Files.deleteIfExists(privateFile); throw failure; }
        System.out.println("Generated Ed25519 publisher key pair. Keep the private key outside the server and repository.");
    }

    private void sign(String[] args) throws IOException, GeneralSecurityException {
        if (args.length != 5) throw new IllegalArgumentException("Usage: sign <private.pem> <keyId> <design.json> <signed-package.json>");
        String keyId = args[2];
        requireKeyId(keyId);
        Path source = Path.of(args[3]).toAbsolutePath().normalize();
        Path destination = Path.of(args[4]).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source) || Files.size(source) > DesignCodec.MAX_DOCUMENT_BYTES)
            throw new IOException("Design input is not a regular file or exceeds the schema document limit");
        if (Files.exists(destination)) throw new IOException("Signed package output already exists");

        DesignDocument design = new DesignCodec().load(Files.readString(source, StandardCharsets.UTF_8));
        PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(readPem(Path.of(args[1]), "PRIVATE KEY")));
        String envelope = signed.sign(design, keyId, privateKey);
        Path parent = destination.getParent();
        if (parent == null) throw new IOException("Signed package output requires a parent directory");
        Files.createDirectories(parent);
        writeNew(destination, (envelope + "\n").getBytes(StandardCharsets.UTF_8), false);
        System.out.println("Signed " + design.kind() + " " + design.metadata().id().value() + " v"
                + design.metadata().version() + " hash=" + design.metadata().hash() + " keyId=" + keyId);
    }

    private void verify(String[] args) throws IOException, GeneralSecurityException {
        if (args.length != 4) throw new IllegalArgumentException("Usage: verify <public.pem> <keyId> <signed-package.json>");
        String keyId = args[2];
        requireKeyId(keyId);
        Path packageFile = Path.of(args[3]).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(packageFile) || !Files.isRegularFile(packageFile)
                || Files.size(packageFile) > SignedDesignCodec.MAX_ENVELOPE_BYTES)
            throw new IOException("Signed package is not regular or exceeds 300 KiB");
        PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(readPem(Path.of(args[1]), "PUBLIC KEY")));
        String envelope = Files.readString(packageFile, StandardCharsets.UTF_8).stripTrailing();
        var imported = signed.verifyAndLoad(envelope, java.util.Map.of(keyId, publicKey));
        System.out.println("Verified " + imported.document().kind() + " " + imported.document().metadata().id().value()
                + " v" + imported.document().metadata().version() + " hash=" + imported.documentHash()
                + " keyId=" + imported.publisherKeyId());
    }

    private static byte[] readPem(Path path, String label) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path) || Files.size(path) > MAX_KEY_BYTES)
            throw new IOException("PEM key is not regular or exceeds 4 KiB");
        String value = Files.readString(path, StandardCharsets.US_ASCII);
        String begin = "-----BEGIN " + label + "-----\n";
        String end = "\n-----END " + label + "-----\n";
        if (!value.startsWith(begin) || !value.endsWith(end)) throw new IOException("Expected one " + label + " PEM block");
        try { return Base64.getDecoder().decode(value.substring(begin.length(), value.length() - end.length()).replace("\n", "")); }
        catch (IllegalArgumentException invalid) { throw new IOException("PEM base64 is malformed", invalid); }
    }

    private static String pem(String label, byte[] encoded) {
        String data = Base64.getEncoder().encodeToString(encoded);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < data.length(); index += 64)
            result.append(data, index, Math.min(index + 64, data.length())).append('\n');
        return "-----BEGIN " + label + "-----\n" + result + "-----END " + label + "-----\n";
    }

    private static void writeNew(Path path, byte[] data, boolean privateKey) throws IOException {
        if (privateKey) {
            try {
                var permissions = java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                Files.createFile(path, permissions);
            } catch (UnsupportedOperationException unsupported) { Files.createFile(path); }
        } else Files.createFile(path);
        try { Files.write(path, data, StandardOpenOption.TRUNCATE_EXISTING); }
        catch (IOException failure) { Files.deleteIfExists(path); throw failure; }
    }

    private static void requireKeyId(String keyId) {
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) throw new IllegalArgumentException("Invalid publisher key ID");
    }

    private static String usage() {
        return "Usage: keygen <private.pem> <public.pem> | sign <private.pem> <keyId> <design.json> <package.json> | verify <public.pem> <keyId> <package.json>";
    }
}
