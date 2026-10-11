package cl.bancoxyz.platform.web;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;

/** Identificador de la instancia: <app>:<hostname>:<pid>. En Docker el hostname es el id del contenedor. */
public record InstanceIdentity(String value) {

    public static InstanceIdentity of(String appName) {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "unknown";
            }
        }
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        return new InstanceIdentity(appName + ":" + host + ":" + pid);
    }
}
