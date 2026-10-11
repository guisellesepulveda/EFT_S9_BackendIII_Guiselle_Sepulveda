package cl.bancoxyz.batch.common;

/** Archivo de entrada ausente o con encabezados incorrectos. No es skippable ni reintentable. */
public class FileValidationException extends RuntimeException {

    public FileValidationException(String message) {
        super(message);
    }
}
