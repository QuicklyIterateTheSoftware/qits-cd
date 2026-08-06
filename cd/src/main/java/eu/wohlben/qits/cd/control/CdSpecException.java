package eu.wohlben.qits.cd.control;

/**
 * The repository's deployment spec could not be read or could not be understood.
 *
 * <p>Deliberately not one of {@code cd.error}'s HTTP-mapped exceptions: this never answers a
 * caller. It ends a deployment — recorded {@code FAILED} with this message in its {@code detail} —
 * because the alternative is guessing a topology, and a guessed topology puts a container on the
 * wrong networks under the wrong name. A missing file is not this: no file means every default, and
 * every repository without one behaves exactly as it did before the file existed.
 */
public class CdSpecException extends RuntimeException {

  public CdSpecException(String message) {
    super(message);
  }

  public CdSpecException(String message, Throwable cause) {
    super(message, cause);
  }
}
