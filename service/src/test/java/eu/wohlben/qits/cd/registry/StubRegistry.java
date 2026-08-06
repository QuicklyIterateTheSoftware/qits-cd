package eu.wohlben.qits.cd.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.cd.entity.CdDeploymentTarget;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * qits-serviceregistry, as much of it as qits-cd talks to, on a real socket — the {@code StubGitHost}
 * arrangement: a stub standing in for another <em>service</em> is written against what that service
 * published, never against its code, and the two repositories share no classpath at all.
 *
 * <pre>
 *   POST   /serviceregistry/api/environments            {name, branch, network}  → 201 {"environment"}
 *   GET    /serviceregistry/api/environments                                     → {"environments"}
 *   GET    /serviceregistry/api/environments/{id}                                → {"environment"}
 *   PATCH  /serviceregistry/api/environments/{id}       {name?, branch?}         → {"environment"}
 *   DELETE /serviceregistry/api/environments/{id}                                → 204
 *   GET    /serviceregistry/api/environments/{id}/links                          → {"services"}
 *   PUT    /serviceregistry/api/services/{name}         {deploymentTarget, …}    → {"service"}
 *   GET    /serviceregistry/api/services                                         → {"services"}
 * </pre>
 *
 * <p>It keeps the semantics cd depends on and nothing else: 409 on a taken environment name, 404 on
 * an unknown id, {@code PUT} replacing a service's whole link set, and {@code links} answering the
 * environment's own services <b>plus every singleton</b> — the rule that makes a new environment
 * pick the singletons up.
 *
 * <p>As a {@link QuarkusTestResourceLifecycleManager} it hands the port to Quarkus <b>before it
 * boots</b>, which is the only way an ephemeral port reaches {@code qits.cd.registry-url}. State is
 * static and shared: {@link #reset()} between tests, distinct environment names per test.
 */
public class StubRegistry implements QuarkusTestResourceLifecycleManager {

  /** The gateway segment the registry serves its API under. */
  public static final String BASE = "/serviceregistry/api";

  private static final ObjectMapper JSON = new ObjectMapper();

  /** One tier, as the registry holds it. */
  public record Env(String id, String name, String branch, String network, Instant createdAt) {}

  /** One service and its links; a singleton has none. */
  public record Svc(
      String name,
      CdDeploymentTarget target,
      String branch,
      boolean availableOnEnv,
      String healthPath,
      List<String> environmentIds,
      Instant createdAt) {}

  private static final Map<String, Env> ENVIRONMENTS =
      Collections.synchronizedMap(new LinkedHashMap<>());
  private static final Map<String, Svc> SERVICES = Collections.synchronizedMap(new LinkedHashMap<>());

  /** Every request that arrived, as {@code METHOD path} — the call-order assertions. */
  private static final List<String> CALLS = Collections.synchronizedList(new ArrayList<>());

  /** When true every route answers nothing at all: the transport failure of an unreachable host. */
  private static volatile boolean offline;

  /** When set every route answers this status instead: the registry erroring rather than absent. */
  private static volatile int failWith;

  /** Called with the environment id the moment a DELETE arrives — the ordering hook. */
  private static volatile Consumer<String> onEnvironmentDelete = id -> {};

  private static HttpServer server;

  public static void reset() {
    ENVIRONMENTS.clear();
    SERVICES.clear();
    CALLS.clear();
    offline = false;
    failWith = 0;
    onEnvironmentDelete = id -> {};
  }

  /** Every route stops answering — what an unreachable qits-serviceregistry looks like to cd. */
  public static void scriptOffline() {
    offline = true;
  }

  /** Every route answers this status — the registry reachable and refusing. */
  public static void scriptStatus(int status) {
    failWith = status;
  }

  /** The registry comes back: a test that asserts what cd wrote during an outage has to read it. */
  public static void scriptOnline() {
    offline = false;
    failWith = 0;
  }

  /** Runs inside the DELETE handler, so a test can look at what cd had already done to docker. */
  public static void onEnvironmentDelete(Consumer<String> hook) {
    onEnvironmentDelete = hook;
  }

  public static List<String> calls() {
    synchronized (CALLS) {
      return List.copyOf(CALLS);
    }
  }

  public static List<Env> environments() {
    synchronized (ENVIRONMENTS) {
      return List.copyOf(ENVIRONMENTS.values());
    }
  }

  public static List<Svc> services() {
    synchronized (SERVICES) {
      return List.copyOf(SERVICES.values());
    }
  }

  public static Svc service(String name) {
    return SERVICES.get(name);
  }

  /** Seed a tier without going through cd — the export tests need a non-empty registry. */
  public static Env seedEnvironment(String name, String branch, String network) {
    Env env = new Env(UUID.randomUUID().toString(), name, branch, network, Instant.now());
    ENVIRONMENTS.put(env.id(), env);
    return env;
  }

  /** Seed a service without a green build — what the registry already held before this test. */
  public static void seedService(Svc service) {
    SERVICES.put(service.name(), service);
  }

  @Override
  public Map<String, String> start() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(Executors.newCachedThreadPool());
      server.createContext(BASE, StubRegistry::answer);
      server.start();
      return Map.of(
          "qits.cd.registry-url", "http://127.0.0.1:" + server.getAddress().getPort());
    } catch (Exception e) {
      throw new IllegalStateException("could not start the stub service registry", e);
    }
  }

  @Override
  public void stop() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  private static void answer(HttpExchange exchange) {
    String method = exchange.getRequestMethod();
    String path = exchange.getRequestURI().getRawPath().substring(BASE.length());
    CALLS.add(method + " " + path);
    if (offline) {
      // No status line at all: the client sees a broken connection, which is the shape a refused
      // or dropped call to an absent registry has.
      exchange.close();
      return;
    }
    try (exchange) {
      if (failWith != 0) {
        send(exchange, failWith, "{\"message\":\"the stub registry was told to fail\"}");
        return;
      }
      String[] segments = path.isEmpty() ? new String[0] : path.substring(1).split("/");
      if (segments.length >= 1 && segments[0].equals("environments")) {
        environments(exchange, method, segments);
        return;
      }
      if (segments.length >= 1 && segments[0].equals("services")) {
        services(exchange, method, segments);
        return;
      }
      send(exchange, 404, "{\"message\":\"no such route\"}");
    } catch (Exception e) {
      throw new IllegalStateException("the stub service registry could not answer", e);
    }
  }

  private static void environments(HttpExchange exchange, String method, String[] segments)
      throws Exception {
    if (segments.length == 1 && method.equals("GET")) {
      ArrayNode all = JSON.createArrayNode();
      environments().forEach(env -> all.add(node(env)));
      send(exchange, 200, JSON.createObjectNode().set("environments", all).toString());
      return;
    }
    if (segments.length == 1 && method.equals("POST")) {
      ObjectNode body = body(exchange);
      String name = body.path("name").asText(null);
      if (environments().stream().anyMatch(env -> env.name().equals(name))) {
        send(exchange, 409, "{\"message\":\"Environment already exists: " + name + "\"}");
        return;
      }
      Env env =
          new Env(
              UUID.randomUUID().toString(),
              name,
              body.path("branch").asText(null),
              body.path("network").asText(null),
              Instant.now());
      ENVIRONMENTS.put(env.id(), env);
      send(exchange, 201, JSON.createObjectNode().set("environment", node(env)).toString());
      return;
    }

    String id = URLDecoder.decode(segments[1], StandardCharsets.UTF_8);
    Env env = ENVIRONMENTS.get(id);
    if (env == null) {
      send(exchange, 404, "{\"message\":\"No such environment: " + id + "\"}");
      return;
    }
    if (segments.length == 3 && segments[2].equals("links") && method.equals("GET")) {
      ArrayNode linked = JSON.createArrayNode();
      for (Svc service : services()) {
        if (service.target() == CdDeploymentTarget.SINGLETON
            || service.environmentIds().contains(id)) {
          linked.add(node(service));
        }
      }
      send(exchange, 200, JSON.createObjectNode().set("services", linked).toString());
      return;
    }
    if (segments.length == 2 && method.equals("GET")) {
      send(exchange, 200, JSON.createObjectNode().set("environment", node(env)).toString());
      return;
    }
    if (segments.length == 2 && method.equals("PATCH")) {
      ObjectNode body = body(exchange);
      String name = body.path("name").asText(env.name());
      String branch = body.path("branch").asText(env.branch());
      if (!name.equals(env.name())
          && environments().stream().anyMatch(other -> other.name().equals(name))) {
        send(exchange, 409, "{\"message\":\"Environment already exists: " + name + "\"}");
        return;
      }
      Env updated = new Env(env.id(), name, branch, env.network(), env.createdAt());
      ENVIRONMENTS.put(env.id(), updated);
      send(exchange, 200, JSON.createObjectNode().set("environment", node(updated)).toString());
      return;
    }
    if (segments.length == 2 && method.equals("DELETE")) {
      onEnvironmentDelete.accept(id);
      ENVIRONMENTS.remove(id);
      // The registry deletes rows only: a service linked nowhere is still a service.
      synchronized (SERVICES) {
        for (Map.Entry<String, Svc> entry : SERVICES.entrySet()) {
          Svc service = entry.getValue();
          if (service.environmentIds().contains(id)) {
            List<String> links = new ArrayList<>(service.environmentIds());
            links.remove(id);
            entry.setValue(
                new Svc(
                    service.name(),
                    service.target(),
                    service.branch(),
                    service.availableOnEnv(),
                    service.healthPath(),
                    List.copyOf(links),
                    service.createdAt()));
          }
        }
      }
      send(exchange, 204, null);
      return;
    }
    send(exchange, 405, "{\"message\":\"not a registry method\"}");
  }

  private static void services(HttpExchange exchange, String method, String[] segments)
      throws Exception {
    if (segments.length == 1 && method.equals("GET")) {
      ArrayNode all = JSON.createArrayNode();
      services().forEach(service -> all.add(node(service)));
      send(exchange, 200, JSON.createObjectNode().set("services", all).toString());
      return;
    }
    if (segments.length == 2 && method.equals("PUT")) {
      String name = URLDecoder.decode(segments[1], StandardCharsets.UTF_8);
      ObjectNode body = body(exchange);
      List<String> links = new ArrayList<>();
      body.path("environmentIds").forEach(id -> links.add(id.asText()));
      Svc existing = SERVICES.get(name);
      Svc service =
          new Svc(
              name,
              CdDeploymentTarget.valueOf(body.path("deploymentTarget").asText("ENVIRONMENT")),
              body.path("branch").isNull() ? null : body.path("branch").asText(null),
              body.path("availableOnEnv").asBoolean(false),
              body.path("healthPath").isNull() ? null : body.path("healthPath").asText(null),
              List.copyOf(links),
              existing == null ? Instant.now() : existing.createdAt());
      SERVICES.put(name, service);
      // 201 on first registration, 200 on every later upsert — the registry's own answer.
      send(
          exchange,
          existing == null ? 201 : 200,
          JSON.createObjectNode().set("service", node(service)).toString());
      return;
    }
    send(exchange, 405, "{\"message\":\"not a registry method\"}");
  }

  private static ObjectNode node(Env env) {
    ObjectNode node = JSON.createObjectNode();
    node.put("id", env.id());
    node.put("name", env.name());
    node.put("branch", env.branch());
    node.put("network", env.network());
    node.put("createdAt", env.createdAt().toString());
    return node;
  }

  private static ObjectNode node(Svc service) {
    ObjectNode node = JSON.createObjectNode();
    node.put("id", service.name());
    node.put("name", service.name());
    node.put("deploymentTarget", service.target().name());
    node.put("branch", service.branch());
    node.put("availableOnEnv", service.availableOnEnv());
    node.put("healthPath", service.healthPath());
    ArrayNode links = node.putArray("environmentIds");
    service.environmentIds().forEach(links::add);
    node.put("createdAt", service.createdAt().toString());
    return node;
  }

  private static ObjectNode body(HttpExchange exchange) throws Exception {
    byte[] raw = exchange.getRequestBody().readAllBytes();
    return raw.length == 0
        ? JSON.createObjectNode()
        : (ObjectNode) JSON.readTree(new String(raw, StandardCharsets.UTF_8));
  }

  private static void send(HttpExchange exchange, int status, String body) throws Exception {
    byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }
}
