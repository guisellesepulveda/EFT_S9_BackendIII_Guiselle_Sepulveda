package cl.bancoxyz.bffatm.security;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import cl.bancoxyz.bffatm.AlertService;
import cl.bancoxyz.bffatm.AtmProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Identidad del cajero a partir de su certificado de cliente (mTLS). Tomcat ya exige y valida el certificado contra la CA
 * (client-auth: need); este filtro es la segunda barrera: toma el CN como ATM-id, verifica su formato y que no esté revocado,
 * y lo expone como atributo de la petición para auditoría y límites. Sin certificado válido no hay operación.
 */
public class ClientCertificateFilter extends OncePerRequestFilter {

    public static final String ATM_ATTR = "atmId";
    private static final String X509_ATTR = "jakarta.servlet.request.X509Certificate";
    private static final Pattern CN = Pattern.compile("(?:^|,)\\s*CN=([^,]+)", Pattern.CASE_INSENSITIVE);

    private final AtmProperties props;
    private final AlertService alerts;
    private final Pattern idPattern;

    public ClientCertificateFilter(AtmProperties props, AlertService alerts) {
        this.props = props;
        this.alerts = alerts;
        this.idPattern = Pattern.compile(props.atmIdPattern());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/atm/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        if (!props.requireClientCert()) {
            req.setAttribute(ATM_ATTR, "ATM-UNVERIFIED");
            chain.doFilter(req, res);
            return;
        }
        X509Certificate[] certs = (X509Certificate[]) req.getAttribute(X509_ATTR);
        if (certs == null || certs.length == 0) {
            alerts.raise("MEDIUM", "ATM_NO_CLIENT_CERT", "atm", "Petición a " + req.getRequestURI() + " sin certificado de cliente");
            reject(res, HttpStatus.UNAUTHORIZED, "CLIENT_CERT_REQUIRED", "Se requiere certificado de cliente (mTLS)");
            return;
        }
        Matcher m = CN.matcher(certs[0].getSubjectX500Principal().getName());
        String atmId = m.find() ? m.group(1).trim() : null;
        if (atmId == null || !idPattern.matcher(atmId).matches()) {
            alerts.raise("MEDIUM", "ATM_INVALID_CERT_SUBJECT", "atm", "Certificado con sujeto no autorizado");
            reject(res, HttpStatus.FORBIDDEN, "ATM_NOT_AUTHORIZED", "Certificado no autorizado para operar como cajero");
            return;
        }
        if (props.revokedAtms().contains(atmId)) {
            alerts.raise("HIGH", "ATM_REVOKED", atmId, "Intento de uso de un cajero revocado");
            reject(res, HttpStatus.FORBIDDEN, "ATM_REVOKED", "Cajero revocado");
            return;
        }
        req.setAttribute(ATM_ATTR, atmId);
        chain.doFilter(req, res);
    }

    static void reject(HttpServletResponse res, HttpStatus status, String code, String detail) throws IOException {
        res.setStatus(status.value());
        res.setContentType("application/problem+json");
        res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase() + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + detail + "\",\"code\":\"" + code + "\"}");
    }
}
