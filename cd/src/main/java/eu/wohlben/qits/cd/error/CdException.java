package eu.wohlben.qits.cd.error;

/**
 * Base for cd errors. Carries an HTTP-ish status code so the web layer can map it to a response
 * without this module depending on JAX-RS (the ci/artifacts stance). The {@code service} module
 * maps these via {@code CdExceptionMapper}.
 */
public class CdException extends RuntimeException {

  private final int statusCode;

  public CdException(int statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }

  public CdException(int statusCode, String message, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  public int statusCode() {
    return statusCode;
  }
}
