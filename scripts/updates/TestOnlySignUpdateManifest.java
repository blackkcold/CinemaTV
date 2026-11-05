// TEST ONLY: disposable fixture signer. Does not invoke or relax SignUpdateManifest.
// Accepts only a short-lived fixture certificate and the fixed, public TEST ONLY password.
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;

class TestOnlySignUpdateManifest {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Expected TEST ONLY keystore and payload paths");
        if (System.getenv().keySet().stream().anyMatch(name -> name.startsWith("CINEMATV_KEYSTORE_") ||
                name.equals("CINEMATV_KEY_PASSWORD") || name.equals("CINEMATV_KEY_ALIAS")))
            throw new IllegalStateException("External production signing configuration is forbidden");
        Path path = Path.of(args[0]).toRealPath();
        for (Path dir = path.getParent(); dir != null; dir = dir.getParent()) {
            Path marker = dir.resolve(".git");
            if (Files.isRegularFile(marker) || Files.isRegularFile(marker.resolve("HEAD")))
                throw new IllegalStateException("Test key must be outside checkout");
        }
        char[] password = "test-only-password".toCharArray();
        try {
            KeyStore store = KeyStore.getInstance(path.toFile(), password);
            X509Certificate certificate = (X509Certificate) store.getCertificate("test-only");
            certificate.checkValidity();
            if (!certificate.getSubjectX500Principal().getName().equals(
                    "CN=TEST ONLY disposable CI fixture,OU=Not for distribution,O=CinemaTV Tests,C=ZZ") ||
                    certificate.getNotAfter().getTime() - certificate.getNotBefore().getTime() > 2L * 86400 * 1000)
                throw new IllegalArgumentException("Only short-lived TEST ONLY certificates may sign fixtures");
            PrivateKey key = (PrivateKey) store.getKey("test-only", password);
            for (String inputName : Arrays.copyOfRange(args, 1, args.length)) {
                Path input = Path.of(inputName).toRealPath();
                if (!input.getFileName().toString().endsWith(".payload.json"))
                    throw new IllegalArgumentException("Expected .payload.json test input");
                if (path.startsWith(input.getParent()))
                    throw new IllegalArgumentException("Keys must be outside artifact directories");
                byte[] payload = Files.readAllBytes(input);
                if (payload.length == 0 || payload.length > 44000) throw new IllegalArgumentException("Payload size");
                Signature signer = Signature.getInstance("SHA256withRSA");
                signer.initSign(key);
                signer.update(payload);
                byte[] signed = signer.sign();
                Signature verifier = Signature.getInstance("SHA256withRSA");
                verifier.initVerify(certificate);
                verifier.update(payload);
                if (!verifier.verify(signed)) throw new SignatureException("TEST ONLY self verification failed");
                Base64.Encoder encoder = Base64.getEncoder();
                String envelope = "{\"schema\":1,\"payload\":\"" + encoder.encodeToString(payload) +
                        "\",\"signature\":\"" + encoder.encodeToString(signed) + "\"}\n";
                Path output = input.resolveSibling(input.getFileName().toString().replace(".payload.json", ".json"));
                Files.writeString(output, envelope, StandardOpenOption.CREATE_NEW);
            }
        } finally { Arrays.fill(password, '\0'); }
    }
}
