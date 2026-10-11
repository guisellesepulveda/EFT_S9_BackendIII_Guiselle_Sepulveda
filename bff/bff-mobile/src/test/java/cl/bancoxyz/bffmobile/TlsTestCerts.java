package cl.bancoxyz.bffmobile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Genera (una vez por JVM de tests) los certificados de desarrollo con el mismo script que usa la plataforma. */
final class TlsTestCerts {

    static final String KEYSTORE_PASSWORD = "test-keystore-pw";
    static final String TRUSTSTORE_PASSWORD = "test-truststore-pw";
    static final Path DIR;

    static {
        try {
            DIR = Files.createTempDirectory("bff-certs");
            ProcessBuilder pb = new ProcessBuilder("bash", "certs/generate-dev-certs.sh", DIR.toString())
                    .directory(Path.of("../..").toAbsolutePath().normalize().toFile())
                    .redirectErrorStream(true);
            pb.environment().put("KEYSTORE_PASSWORD", KEYSTORE_PASSWORD);
            pb.environment().put("TRUSTSTORE_PASSWORD", TRUSTSTORE_PASSWORD);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            if (p.waitFor() != 0) {
                throw new IllegalStateException("No se pudieron generar los certificados:\n" + out);
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private TlsTestCerts() {
    }

    static String keystore() {
        return "file:" + DIR.resolve("server.p12");
    }

    static String truststore() {
        return "file:" + DIR.resolve("truststore.p12");
    }
}
