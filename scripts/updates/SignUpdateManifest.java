// Run only on the approved signing machine, never GitHub Actions.
// Java 17 source launcher: java SignUpdateManifest.java payload.json update.json
// Reads an existing external keystore; does not generate keys or contact any service.
import java.nio.file.*;
import java.security.*;
import java.util.*;

class SignUpdateManifest {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected public payload and output paths");
        if (System.getenv("GITHUB_ACTIONS") != null)
            throw new IllegalStateException("Production signing must not run on GitHub Actions");
        Path storePath = Path.of(required("CINEMATV_KEYSTORE_PATH")).toRealPath();
        // Prevent accidental use of a key under any repository checkout.
        for (Path directory = storePath.getParent(); directory != null; directory = directory.getParent()) {
            Path marker = directory.resolve(".git");
            if (Files.isRegularFile(marker) || Files.isRegularFile(marker.resolve("HEAD")))
                throw new IllegalStateException("Keystore must be outside every Git repository");
        }
        byte[] payload = Files.readAllBytes(Path.of(args[0]));
        if (payload.length == 0 || payload.length > 44000)
            throw new IllegalArgumentException("Invalid payload size");
        char[] storePassword = required("CINEMATV_KEYSTORE_PASSWORD").toCharArray();
        char[] keyPassword = required("CINEMATV_KEY_PASSWORD").toCharArray();
        try {
            KeyStore store = KeyStore.getInstance(storePath.toFile(), storePassword);
            String alias = required("CINEMATV_KEY_ALIAS");
            PrivateKey key = (PrivateKey) store.getKey(alias, keyPassword);
            if (key == null) throw new IllegalStateException("Signing alias unavailable");
            String algorithm = switch (key.getAlgorithm()) {
                case "RSA" -> "SHA256withRSA";
                case "EC" -> "SHA256withECDSA";
                default -> throw new IllegalStateException("Unsupported signing algorithm");
            };
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(key);
            signer.update(payload);
            byte[] signed = signer.sign();
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(store.getCertificate(alias));
            verifier.update(payload);
            if (!verifier.verify(signed)) throw new SignatureException("Self verification failed");
            Base64.Encoder encoder = Base64.getEncoder();
            String envelope = "{\"schema\":1,\"payload\":\"" + encoder.encodeToString(payload) +
                    "\",\"signature\":\"" + encoder.encodeToString(signed) + "\"}\n";
            Files.writeString(Path.of(args[1]), envelope, StandardOpenOption.CREATE_NEW);
            System.out.println("Signed public update manifest. No key material was exported.");
        } finally {
            Arrays.fill(storePassword, '\0');
            Arrays.fill(keyPassword, '\0');
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
}
