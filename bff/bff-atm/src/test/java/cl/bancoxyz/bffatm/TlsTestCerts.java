package cl.bancoxyz.bffatm;

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

    /** Certificado de cliente de un p12 generado por el script (cajero ATM-0001 o el del atacante). */
    static java.security.cert.X509Certificate clientCert(String p12) {
        try (java.io.InputStream in = Files.newInputStream(DIR.resolve(p12))) {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            ks.load(in, KEYSTORE_PASSWORD.toCharArray());
            return (java.security.cert.X509Certificate) ks.getCertificate(ks.aliases().nextElement());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String truststore() {
        return "file:" + DIR.resolve("truststore.p12");
    }
}
