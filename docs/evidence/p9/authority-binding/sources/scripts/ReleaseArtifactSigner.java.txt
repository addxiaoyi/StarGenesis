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
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Creates and verifies an Ed25519-signed manifest for a bounded release artifact set. */
public final class ReleaseArtifactSigner {
    private static final String HEADER = "StarGenesis-Release/v1";
    private static final int MAX_ARTIFACTS = 32;
    private static final long MAX_ARTIFACT_BYTES = 512L * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 16 * 1024;
    private static final Pattern KEY_ID = Pattern.compile("[a-z0-9][a-z0-9_.:-]{0,63}");
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern RELEASE_VERSION = Pattern.compile(
            "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?");

    public static void main(String[] args) {
        try {
            if (args.length == 0) throw new IllegalArgumentException(usage());
            switch (args[0]) {
                case "keygen" -> keygen(args);
                case "sign" -> sign(args);
                case "verify" -> verify(args);
                default -> throw new IllegalArgumentException(usage());
            }
        } catch (IOException | GeneralSecurityException | IllegalArgumentException failure) {
            System.err.println("Release artifact operation failed: " + failure.getMessage());
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
        try {
            writeNew(publicFile, pem("PUBLIC KEY", pair.getPublic().getEncoded()).getBytes(StandardCharsets.US_ASCII), false);
        } catch (IOException failure) {
            Files.deleteIfExists(privateFile);
            throw failure;
        }
        System.out.println("Generated Ed25519 release key pair. Keep the private key outside the repository.");
    }

    private static void sign(String[] args) throws IOException, GeneralSecurityException {
        if (args.length < 6) throw new IllegalArgumentException("Usage: sign <private.pem> <keyId> <version> <manifest> <artifact>...");
        String keyId = args[2];
        requireKeyId(keyId);
        String version = args[3];
        requireReleaseVersion(version);
        Path manifestPath = Path.of(args[4]).toAbsolutePath().normalize();
        Path manifestDirectory = manifestPath.getParent();
        if (manifestDirectory == null) throw new IOException("Manifest requires a parent directory");
        if (Files.exists(manifestPath)) throw new IOException("Release manifest already exists");
        PrivateKey privateKey = decodePrivateKey(readPem(Path.of(args[1]), "PRIVATE KEY"));
        Map<String, Artifact> artifacts = new TreeMap<>();
        for (int index = 5; index < args.length; index++) {
            Path artifactPath = Path.of(args[index]).toAbsolutePath().normalize();
            Artifact artifact = inspect(artifactPath);
            if (artifacts.putIfAbsent(artifact.fileName(), artifact) != null)
                throw new IOException("Artifact filenames must be unique in the signed set");
        }
        if (artifacts.isEmpty() || artifacts.size() > MAX_ARTIFACTS)
            throw new IOException("Release must contain 1-32 artifacts");

        String body = manifestBody(keyId, version, artifacts.values().stream().toList());
        byte[] signature = signBytes(body, privateKey);
        String encoded = body + "signature=" + Base64.getEncoder().withoutPadding().encodeToString(signature) + "\n";
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_MANIFEST_BYTES)
            throw new IOException("Release manifest exceeds 16 KiB");
        Files.createDirectories(manifestDirectory);
        writeNew(manifestPath, encoded.getBytes(StandardCharsets.UTF_8), false);
        System.out.println("Signed " + artifacts.size() + " release artifacts with key " + keyId);
    }

    private static void verify(String[] args) throws IOException, GeneralSecurityException {
        if (args.length != 3) throw new IllegalArgumentException("Usage: verify <public.pem> <manifest>");
        Path manifestPath = Path.of(args[2]).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(manifestPath) || !Files.isRegularFile(manifestPath)
                || Files.size(manifestPath) > MAX_MANIFEST_BYTES)
            throw new IOException("Release manifest is not regular or exceeds its size limit");
        byte[] data = Files.readAllBytes(manifestPath);
        String manifest = decodeUtf8(data);
        if (!manifest.endsWith("\n")) throw new IOException("Release manifest must end with a newline");
        int signatureStart = manifest.lastIndexOf("signature=");
        if (signatureStart < 0 || signatureStart > 0 && manifest.charAt(signatureStart - 1) != '\n')
            throw new IOException("Release signature line is missing or malformed");
        String body = manifest.substring(0, signatureStart);
        String signatureLine = manifest.substring(signatureStart, manifest.length() - 1);
        if (signatureLine.indexOf('\n') >= 0) throw new IOException("Unexpected data after release signature");
        String encodedSignature = signatureLine.substring("signature=".length());
        byte[] signature;
        try { signature = Base64.getDecoder().decode(encodedSignature); }
        catch (IllegalArgumentException invalid) { throw new IOException("Malformed release signature", invalid); }
        if (signature.length != 64 || !Base64.getEncoder().withoutPadding().encodeToString(signature).equals(encodedSignature))
            throw new IOException("Release signature encoding is not canonical");

        List<Artifact> artifacts = parseBody(body);
        PublicKey publicKey = decodePublicKey(readPem(Path.of(args[1]), "PUBLIC KEY"));
        if (!verifyBytes(body, signature, publicKey)) throw new IOException("Release manifest signature is invalid");
        Path directory = manifestPath.getParent();
        for (Artifact artifact : artifacts) {
            Artifact actual = inspect(directory.resolve(artifact.fileName()));
            if (actual.size() != artifact.size() || !actual.sha256().equals(artifact.sha256()))
                throw new IOException("Release artifact does not match the signed manifest: " + artifact.fileName());
        }
        System.out.println("Verified " + artifacts.size() + " release artifacts with Ed25519 signature");
    }

    private static String manifestBody(String keyId, String version, List<Artifact> artifacts) {
        StringBuilder body = new StringBuilder(HEADER).append('\n')
                .append("algorithm=Ed25519\n")
                .append("keyId=").append(keyId).append('\n')
                .append("version=").append(version).append('\n');
        for (Artifact artifact : artifacts) {
            body.append("artifact=").append(artifact.fileName()).append('\t')
                    .append(artifact.size()).append('\t').append(artifact.sha256()).append('\n');
        }
        return body.toString();
    }

    private static List<Artifact> parseBody(String body) throws IOException {
        String[] lines = body.split("\n", -1);
        if (lines.length < 6 || !HEADER.equals(lines[0]) || !"algorithm=Ed25519".equals(lines[1])
                || !lines[2].startsWith("keyId=") || !lines[3].startsWith("version=")
                || !lines[lines.length - 1].isEmpty())
            throw new IOException("Release manifest header or structure is invalid");
        String keyId = lines[2].substring("keyId=".length());
        requireKeyId(keyId);
        requireReleaseVersion(lines[3].substring("version=".length()));
        List<Artifact> artifacts = new ArrayList<>();
        String previousName = "";
        for (int index = 4; index < lines.length - 1; index++) {
            String[] fields = lines[index].split("\t", -1);
            if (fields.length != 3 || !fields[0].startsWith("artifact="))
                throw new IOException("Release artifact entry is malformed");
            String name = fields[0].substring("artifact=".length());
            if (!FILE_NAME.matcher(name).matches() || name.equals(".") || name.equals("..")
                    || name.compareTo(previousName) <= 0)
                throw new IOException("Release artifact names must be safe, unique, and sorted");
            long size;
            try { size = Long.parseLong(fields[1]); }
            catch (NumberFormatException invalid) { throw new IOException("Release artifact size is invalid", invalid); }
            if (size < 0 || size > MAX_ARTIFACT_BYTES || !fields[2].matches("[0-9a-f]{64}"))
                throw new IOException("Release artifact bounds or digest are invalid");
            artifacts.add(new Artifact(name, size, fields[2]));
            previousName = name;
        }
        if (artifacts.isEmpty() || artifacts.size() > MAX_ARTIFACTS)
            throw new IOException("Release must contain 1-32 artifacts");
        return List.copyOf(artifacts);
    }

    private static Artifact inspect(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        String fileName = absolute.getFileName().toString();
        if (!FILE_NAME.matcher(fileName).matches() || Files.isSymbolicLink(absolute)
                || !Files.isRegularFile(absolute))
            throw new IOException("Release artifact must be a regular file with a safe basename");
        long size = Files.size(absolute);
        if (size > MAX_ARTIFACT_BYTES) throw new IOException("Release artifact exceeds 512 MiB");
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(absolute)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            return new Artifact(fileName, size, HexFormat.of().formatHex(digest.digest()));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static byte[] signBytes(String body, PrivateKey key) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(body.getBytes(StandardCharsets.UTF_8));
        return signer.sign();
    }

    private static boolean verifyBytes(String body, byte[] signatureBytes, PublicKey key) throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(body.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(signatureBytes);
    }

    private static PrivateKey decodePrivateKey(byte[] encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    private static PublicKey decodePublicKey(byte[] encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    }

    private static byte[] readPem(Path path, String label) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path) || Files.size(path) > 4096)
            throw new IOException("PEM key is not regular or exceeds 4 KiB");
        String pem = Files.readString(path, StandardCharsets.US_ASCII);
        String begin = "-----BEGIN " + label + "-----\n";
        String end = "\n-----END " + label + "-----\n";
        if (!pem.startsWith(begin) || !pem.endsWith(end)) throw new IOException("Expected one " + label + " PEM block");
        try { return Base64.getDecoder().decode(pem.substring(begin.length(), pem.length() - end.length()).replace("\n", "")); }
        catch (IllegalArgumentException invalid) { throw new IOException("Malformed PEM base64", invalid); }
    }

    private static String pem(String label, byte[] encoded) {
        String base64 = Base64.getEncoder().encodeToString(encoded);
        StringBuilder wrapped = new StringBuilder();
        for (int index = 0; index < base64.length(); index += 64)
            wrapped.append(base64, index, Math.min(index + 64, base64.length())).append('\n');
        return "-----BEGIN " + label + "-----\n" + wrapped + "-----END " + label + "-----\n";
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IOException("Release manifest is not valid UTF-8", invalid);
        }
    }

    private static void writeNew(Path path, byte[] data, boolean privateKey) throws IOException {
        Path parent = path.getParent();
        if (parent == null) throw new IOException("Output file requires a parent directory");
        Files.createDirectories(parent);
        if (privateKey) {
            try {
                var permissions = java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                Files.createFile(path, permissions);
            } catch (UnsupportedOperationException unsupported) {
                Files.createFile(path);
            }
        } else Files.createFile(path);
        try { Files.write(path, data, StandardOpenOption.TRUNCATE_EXISTING); }
        catch (IOException failure) { Files.deleteIfExists(path); throw failure; }
    }

    private static void requireKeyId(String keyId) {
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) throw new IllegalArgumentException("Invalid release key ID");
    }

    private static void requireReleaseVersion(String version) {
        if (version == null || !RELEASE_VERSION.matcher(version).matches() || version.toUpperCase(java.util.Locale.ROOT).contains("SNAPSHOT"))
            throw new IllegalArgumentException("Release version must be a non-SNAPSHOT semantic version");
    }

    private static String usage() {
        return "Usage: keygen <private.pem> <public.pem> | sign <private.pem> <keyId> <version> <manifest> <artifact>... | verify <public.pem> <manifest>";
    }

    private record Artifact(String fileName, long size, String sha256) { }
}
