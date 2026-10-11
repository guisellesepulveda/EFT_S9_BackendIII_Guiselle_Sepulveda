package cl.bancoxyz.bff.common;

import java.util.List;

/** Página en memoria (los BFF paginan sobre los resultados ya autorizados del usuario). */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static <T> PageResponse<T> of(List<T> all, int page, int size, int maxSize) {
        int s = Math.min(Math.max(size, 1), maxSize);
        int p = Math.max(page, 0);
        int from = (int) Math.min((long) p * s, all.size());
        int to = Math.min(from + s, all.size());
        return new PageResponse<>(List.copyOf(all.subList(from, to)), p, s, all.size(), (int) Math.ceil(all.size() / (double) s));
    }
}
