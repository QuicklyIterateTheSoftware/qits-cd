package eu.wohlben.qits.cd.api;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * The platform after the enforcement flip: {@code qits.cd.legacy-network} empty.
 *
 * <p>That one empty value is the whole of the flip — from then on a container is reachable only
 * through its own network, its environment's hub, or a gateway route, and a direct cross-application
 * URL nobody migrated fails loudly instead of resolving on a flat network. It is a later phase in
 * the deployment, and a test here so that the code path it turns on is already proven when someone
 * empties the key.
 */
public class LegacyNetworkOffProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("qits.cd.legacy-network", "");
  }
}
