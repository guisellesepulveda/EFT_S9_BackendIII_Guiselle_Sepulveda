package cl.bancoxyz.platform.security;

/**
 * Expresiones de autorización por scope/rol, compartidas por los servicios.
 * Cada canal tiene sus scopes (web:*, mobile:*, atm:operate); internal es la comunicación servicio-a-servicio.
 */
public final class Authz {

    public static final String READ = "hasAnyAuthority('SCOPE_web:read','SCOPE_mobile:read','SCOPE_atm:operate','SCOPE_internal')";
    public static final String WRITE = "hasAnyAuthority('SCOPE_web:write','SCOPE_mobile:transfer','SCOPE_atm:operate','SCOPE_internal')";
    public static final String INTERNAL = "hasAuthority('SCOPE_internal')";
    public static final String ADMIN = "hasRole('ADMIN')";

    private Authz() {
    }
}
