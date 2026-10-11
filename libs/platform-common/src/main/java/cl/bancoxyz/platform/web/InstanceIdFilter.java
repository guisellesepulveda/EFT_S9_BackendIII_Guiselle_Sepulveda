package cl.bancoxyz.platform.web;

import java.io.IOException;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Agrega X-Instance-Id a cada respuesta: evidencia del balanceo entre instancias al escalar con --scale. */
public class InstanceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Instance-Id";

    private final String instanceId;

    public InstanceIdFilter(String instanceId) {
        this.instanceId = instanceId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HEADER, instanceId);
        chain.doFilter(request, response);
    }
}
