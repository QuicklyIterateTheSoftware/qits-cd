package eu.wohlben.qits.cd.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cd.control.RegistryClient;
import eu.wohlben.qits.cd.entity.CdDeploymentTarget;
import eu.wohlben.qits.cd.error.BadRequestException;
import eu.wohlben.qits.cd.error.ConflictException;
import eu.wohlben.qits.cd.error.NotFoundException;
import eu.wohlben.qits.cd.error.RegistryException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The sole production implementation of {@link RegistryClient}: plain {@code java.net.http} against
 * qits-serviceregistry, the {@code GitHostSpecSource} arrangement again — the JDK's own client, no
 * generated stub, no shared model. The two repositories share no code; the wire contract below is
 * the interface, and the suites stand a real server up on it.
 *
 * <pre>
 *   POST   &lt;base&gt;/serviceregistry/api/environments            {name, branch, network}
 *   GET    &lt;base&gt;/serviceregistry/api/environments
 *   GET    &lt;base&gt;/serviceregistry/api/environments/{id}
 *   PATCH  &lt;base&gt;/serviceregistry/api/environments/{id}       {name?, branch?}
 *   DELETE &lt;base&gt;/serviceregistry/api/environments/{id}
 *   GET    &lt;base&gt;/serviceregistry/api/environments/{id}/links
 *   PUT    &lt;base&gt;/serviceregistry/api/services/{name}         {deploymentTarget, branch,
 *                                                              availableOnEnv, healthPath,
 *                                                              environmentIds[]}
 *   GET    &lt;base&gt;/serviceregistry/api/services
 * </pre>
 *
 * <p><b>Status codes are translated, not wrapped.</b> The registry ships cd's own validation
 * vocabulary, so its 400/404/409 come back as cd's exceptions and the proxied surface answers what
 * it always answered. Everything else — a refused connection, a 500, a timeout, a body that does
 * not parse — is a {@link RegistryException} (502): the caller's request was fine, the thing behind
 * cd was not.
 *
 * <p><b>Reading is deliberately tolerant about the envelope.</b> A list is taken from the named key
 * ({@code environments} / {@code services}), from any single array the object holds, or from a bare
 * array; a service's links are read from {@code environmentIds}, from a {@code links} array, or
 * from a flattened row's single {@code environmentId}, and rows sharing a name are merged. Two
 * services are being written against one prose contract, and a read that survives the envelope
 * spelling fails only when the substance disagrees — which is what a wire mismatch should be.
 */
@ApplicationScoped
public class HttpRegistryClient implements RegistryClient {

  /** The gateway segment qits-serviceregistry serves its API under. */
  private static final String BASE = "/serviceregistry/api";

  @Inject ObjectMapper json;

  /**
   * The machine credential, when a deployment has configured one. The registry guards its writes;
   * this is attached to every call, reads included, because one code path is fewer things to get
   * wrong than two.
   */
  @Inject RegistryBearer bearer;

  @ConfigProperty(name = "qits.cd.registry-url")
  String registryUrl;

  @ConfigProperty(name = "qits.cd.registry-timeout-seconds")
  long timeoutSeconds;

  /**
   * One client for the life of the process — the {@code GitHostSpecSource} arrangement, and for the
   * same reason: a {@code HttpClient} owns a selector thread and a connection pool. Built lazily
   * because the timeout it is configured with is a config value.
   */
  private volatile HttpClient client;

  private HttpClient client() {
    HttpClient existing = client;
    if (existing == null) {
      synchronized (this) {
        existing = client;
        if (existing == null) {
          existing =
              HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();
          client = existing;
        }
      }
    }
    return existing;
  }

  @Override
  public RegEnvironment createEnvironment(String name, String branch, String network) {
    ObjectNode body = json.createObjectNode();
    body.put("name", name);
    body.put("branch", branch);
    body.put("network", network);
    return environmentOf(send("POST", "/environments", body));
  }

  @Override
  public RegEnvironment updateEnvironment(String environmentId, String name, String branch) {
    ObjectNode body = json.createObjectNode();
    if (name != null) {
      body.put("name", name);
    }
    if (branch != null) {
      body.put("branch", branch);
    }
    return environmentOf(send("PATCH", "/environments/" + segment(environmentId), body));
  }

  @Override
  public void deleteEnvironment(String environmentId) {
    send("DELETE", "/environments/" + segment(environmentId), null);
  }

  @Override
  public RegEnvironment environment(String environmentId) {
    return environmentOf(send("GET", "/environments/" + segment(environmentId), null));
  }

  @Override
  public List<RegEnvironment> environments() {
    List<RegEnvironment> found = new ArrayList<>();
    for (JsonNode node : array(send("GET", "/environments", null), "environments")) {
      found.add(readEnvironment(node));
    }
    return List.copyOf(found);
  }

  @Override
  public List<RegEnvironment> environmentsOnBranch(String branch) {
    List<RegEnvironment> matching = new ArrayList<>();
    for (RegEnvironment environment : environments()) {
      if (branch.equals(environment.branch())) {
        matching.add(environment);
      }
    }
    return List.copyOf(matching);
  }

  @Override
  public RegService upsertService(ServiceUpsert upsert) {
    ObjectNode body = json.createObjectNode();
    body.put("deploymentTarget", upsert.target().name());
    body.put("branch", upsert.branch());
    body.put("availableOnEnv", upsert.availableOnEnv());
    body.put("healthPath", upsert.healthPath());
    ArrayNode links = body.putArray("environmentIds");
    upsert.environmentIds().forEach(links::add);
    JsonNode answered = send("PUT", "/services/" + segment(upsert.name()), body);
    List<RegService> services = readServices(answered, "services");
    return services.isEmpty()
        ? new RegService(
            upsert.name(),
            upsert.target(),
            upsert.branch(),
            upsert.availableOnEnv(),
            upsert.healthPath(),
            upsert.environmentIds(),
            null)
        : services.get(0);
  }

  @Override
  public List<RegService> services() {
    return readServices(send("GET", "/services", null), "services");
  }

  @Override
  public List<RegService> linksOf(String environmentId) {
    return readServices(
        send("GET", "/environments/" + segment(environmentId) + "/links", null), "services");
  }

  // --- the wire ---------------------------------------------------------------------------------

  private JsonNode send(String method, String path, JsonNode body) {
    String url = trimTrailingSlash(registryUrl) + BASE + path;
    // Minted before the request rather than on a 401 retry: a token this service cannot get is the
    // same outcome as a registry it cannot reach, and it says so with the cause.
    Optional<String> authorization = bearer.header();
    HttpResponse<String> response;
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(timeoutSeconds))
              .header("Accept", "application/json");
      authorization.ifPresent(value -> request.header("Authorization", value));
      if (body == null) {
        request.method(method, HttpRequest.BodyPublishers.noBody());
      } else {
        request
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      }
      response = client().send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RegistryException("interrupted while calling " + url, e);
    } catch (Exception e) {
      throw new RegistryException("could not reach " + url + ": " + e, e);
    }

    int status = response.statusCode();
    if (status >= 200 && status < 300) {
      return parse(url, response.body());
    }
    String message = messageOf(response.body(), url + " answered " + status);
    switch (status) {
      case 400 -> throw new BadRequestException(message);
      case 404 -> throw new NotFoundException(message);
      case 409 -> throw new ConflictException(message);
      default -> throw new RegistryException(url + " answered " + status + ": " + message);
    }
  }

  private JsonNode parse(String url, String body) {
    if (body == null || body.isBlank()) {
      return json.createObjectNode();
    }
    try {
      return json.readTree(body);
    } catch (Exception e) {
      throw new RegistryException("could not read the answer of " + url + ": " + e, e);
    }
  }

  /** The registry's own {@code {"message":…}} error shape, or the fallback. */
  private String messageOf(String body, String fallback) {
    if (body == null || body.isBlank()) {
      return fallback;
    }
    try {
      JsonNode node = json.readTree(body);
      JsonNode message = node.get("message");
      return message == null || message.isNull() ? fallback : message.asText();
    } catch (Exception e) {
      return fallback;
    }
  }

  // --- reading ----------------------------------------------------------------------------------

  private RegEnvironment environmentOf(JsonNode answered) {
    return readEnvironment(object(answered, "environment"));
  }

  private RegEnvironment readEnvironment(JsonNode node) {
    return new RegEnvironment(
        text(node, "id"),
        text(node, "name"),
        text(node, "branch"),
        text(node, "network"),
        instant(node, "createdAt"));
  }

  /** Every service in the answer, rows sharing a name merged into one with the union of its links. */
  private List<RegService> readServices(JsonNode answered, String key) {
    Map<String, RegService> byName = new LinkedHashMap<>();
    for (JsonNode node : array(answered, key)) {
      RegService service = readService(node);
      if (service.name() == null) {
        continue;
      }
      RegService merged = byName.get(service.name());
      if (merged == null) {
        byName.put(service.name(), service);
        continue;
      }
      Set<String> links = new LinkedHashSet<>(merged.environmentIds());
      links.addAll(service.environmentIds());
      byName.put(
          service.name(),
          new RegService(
              merged.name(),
              merged.target(),
              merged.branch(),
              merged.availableOnEnv() || service.availableOnEnv(),
              merged.healthPath() != null ? merged.healthPath() : service.healthPath(),
              List.copyOf(links),
              merged.createdAt() != null ? merged.createdAt() : service.createdAt()));
    }
    return List.copyOf(byName.values());
  }

  private RegService readService(JsonNode node) {
    String target = text(node, "deploymentTarget");
    if (target == null) {
      target = text(node, "target");
    }
    return new RegService(
        text(node, "name"),
        CdDeploymentTarget.SINGLETON.name().equals(target)
            ? CdDeploymentTarget.SINGLETON
            : CdDeploymentTarget.ENVIRONMENT,
        text(node, "branch"),
        node.path("availableOnEnv").asBoolean(false),
        text(node, "healthPath"),
        links(node),
        instant(node, "createdAt"));
  }

  /** The link set, however the answer spells it: a list of ids, a list of links, or one id. */
  private List<String> links(JsonNode node) {
    Set<String> links = new LinkedHashSet<>();
    JsonNode ids = node.get("environmentIds");
    if (ids != null && ids.isArray()) {
      ids.forEach(id -> add(links, id.asText(null)));
    }
    JsonNode linked = node.get("links");
    if (linked != null && linked.isArray()) {
      for (JsonNode one : linked) {
        add(links, one.isTextual() ? one.asText() : text(one, "environmentId"));
      }
    }
    add(links, text(node, "environmentId"));
    return List.copyOf(links);
  }

  private static void add(Set<String> links, String id) {
    if (id != null && !id.isBlank()) {
      links.add(id);
    }
  }

  /**
   * The array under {@code key}, or the object's only array, or the document itself when it is one.
   */
  private Iterable<JsonNode> array(JsonNode answered, String key) {
    if (answered == null || answered.isNull()) {
      return List.of();
    }
    if (answered.isArray()) {
      return answered;
    }
    JsonNode named = answered.get(key);
    if (named != null && named.isArray()) {
      return named;
    }
    for (JsonNode child : answered) {
      if (child.isArray()) {
        return child;
      }
    }
    return List.of();
  }

  /** The object under {@code key}, or the document itself when it is the object. */
  private JsonNode object(JsonNode answered, String key) {
    if (answered == null || answered.isNull()) {
      throw new RegistryException("qits-serviceregistry answered no " + key);
    }
    JsonNode named = answered.get(key);
    if (named != null && named.isObject()) {
      return named;
    }
    if (answered.has("id")) {
      return answered;
    }
    throw new RegistryException("qits-serviceregistry answered no " + key + ": " + answered);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static Instant instant(JsonNode node, String field) {
    String value = text(node, field);
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (RuntimeException e) {
      return null; // a timestamp cd only forwards is never worth failing a read over
    }
  }

  private static String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String trimTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
