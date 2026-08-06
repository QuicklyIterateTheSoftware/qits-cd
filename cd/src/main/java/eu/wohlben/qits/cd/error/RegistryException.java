package eu.wohlben.qits.cd.error;

/**
 * 502 — qits-serviceregistry could not be reached, or answered something cd cannot use.
 *
 * <p>A gateway status rather than a 500 because that is what it is: the registry is the system of
 * record for environments and services, and cd's environment endpoints are a door onto it. The
 * caller's request was fine; the thing behind cd was not.
 *
 * <p>On the deployment path this never answers anyone — it ends up as the {@code detail} of a
 * {@code FAILED} deployment, exactly like {@link eu.wohlben.qits.cd.control.CdSpecException}.
 */
public class RegistryException extends CdException {

  public RegistryException(String message) {
    super(502, message);
  }

  public RegistryException(String message, Throwable cause) {
    super(502, message, cause);
  }
}
