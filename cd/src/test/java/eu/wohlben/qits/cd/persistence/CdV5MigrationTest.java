package eu.wohlben.qits.cd.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * V5 against a database cd v1 actually wrote — the only way to test a backfill, since the suites run
 * every migration against an empty schema and a backfill on nothing proves nothing.
 *
 * <p>It migrates to V4, writes the rows a live cd v1 would have, migrates the rest of the way, and
 * asserts the three things the release depends on:
 *
 * <ul>
 *   <li>every deployment carries its application's name and its tier, singletons included (their
 *       environment stays null, which is what a singleton IS);
 *   <li>the startup sweep's adoption still finds the prior {@code ACTIVE} row of a {@code STARTING}
 *       row cd v1 wrote — the pair {@code (application_name, environment_id)} replaced "the same
 *       application_id", and if the backfill missed either, cd's own self-update would come up
 *       adopting nothing;
 *   <li>the deployment table no longer needs an application row at all.
 * </ul>
 *
 * <p>Plain JUnit and a real H2: no Quarkus, because the subject is the SQL.
 */
public class CdV5MigrationTest {

  private static final String ENV = "env-v1";
  private static final String APP = "app-v1";
  private static final String SINGLETON = "singleton-v1";

  @Test
  public void v5BackfillsTheNamesTheTiersAndTheOrder() throws Exception {
    String url = "jdbc:h2:mem:cd-v5-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    migrate(url, "4");

    try (Connection connection = DriverManager.getConnection(url, "sa", "");
        Statement sql = connection.createStatement()) {
      sql.execute(
          "insert into cd_environment values ('"
              + ENV
              + "', 'dev', 'environment/dev', 'qits-net', timestamp with time zone"
              + " '2026-08-01 10:00:00Z')");
      sql.execute(
          "insert into cd_application (id, environment_id, repo_id, name, health_path, created_at,"
              + " deployment_target, branch, available_on_env) values ('"
              + APP
              + "', '"
              + ENV
              + "', 'qits-cd', 'qits-cd', null, timestamp with time zone '2026-08-01 10:00:00Z',"
              + " 'ENVIRONMENT', null, false)");
      sql.execute(
          "insert into cd_application (id, environment_id, repo_id, name, health_path, created_at,"
              + " deployment_target, branch, available_on_env) values ('"
              + SINGLETON
              + "', null, 'qits-idp', 'qits-idp', null, timestamp with time zone"
              + " '2026-08-01 10:00:00Z', 'SINGLETON', 'main', false)");

      // The history a live cd v1 leaves behind, oldest first — including the one row the whole
      // self-update handoff hangs on: STARTING, named after a container that is now this process.
      deployment(sql, "d-1", APP, "a".repeat(40), "DECOMMISSIONED", "2026-08-01 11:00:00Z");
      deployment(sql, "d-2", APP, "b".repeat(40), "ACTIVE", "2026-08-01 12:00:00Z");
      deployment(sql, "d-3", APP, "c".repeat(40), "STARTING", "2026-08-01 13:00:00Z");
      deployment(sql, "d-4", SINGLETON, "d".repeat(40), "ACTIVE", "2026-08-01 14:00:00Z");
    }

    migrate(url, null);

    try (Connection connection = DriverManager.getConnection(url, "sa", "");
        Statement sql = connection.createStatement()) {
      assertEquals(List.of("qits-cd|" + ENV, "qits-cd|" + ENV, "qits-cd|" + ENV, "qits-idp|null"),
          rows(
              sql,
              "select application_name || '|' || coalesce(environment_id, 'null') from"
                  + " cd_deployment order by created_at"),
          "every row carries its application's name and its tier; a singleton's stays null");

      assertEquals(
          List.of("d-1", "d-2", "d-3", "d-4"),
          rows(sql, "select id from cd_deployment order by seq"),
          "the ordering column follows the order the history was recorded in");

      // The sweep's adoption, as SQL: the STARTING row's (name, tier) has to find the ACTIVE row
      // that preceded it. This is the query that decides whether a self-updating cd comes back
      // ACTIVE or comes back having failed its own deployment.
      assertEquals(
          List.of("d-2"),
          rows(
              sql,
              "select p.id from cd_deployment p join cd_deployment o on o.id = 'd-3'"
                  + " where p.application_name = o.application_name"
                  + " and (p.environment_id = o.environment_id"
                  + "      or (p.environment_id is null and o.environment_id is null))"
                  + " and p.status = 'ACTIVE'"),
          "the predecessor of the row cd v1 left STARTING is still findable");

      // The FK is gone, so a deployment no longer needs an application row to exist.
      sql.execute(
          "insert into cd_deployment (id, application_name, environment_id, commit_sha, status,"
              + " created_at) values ('d-5', 'a-brand-new-service', 'a-tier-no-row-describes',"
              + " '" + "e".repeat(40) + "', 'QUEUED', timestamp with time zone"
              + " '2026-08-01 15:00:00Z')");
      try (ResultSet answered =
          sql.executeQuery("select seq from cd_deployment where id = 'd-5'")) {
        assertTrue(answered.next());
        assertNotNull(answered.getObject(1), "the database assigns the ordering column itself");
        assertTrue(
            answered.getLong(1) > 4,
            "and it continues past the values the backfill assigned: " + answered.getLong(1));
      }
    }
  }

  private static void migrate(String url, String target) {
    Flyway.configure()
        .dataSource(url, "sa", "")
        .locations("classpath:db/cd/migration")
        .target(target == null ? MigrationVersion.LATEST : MigrationVersion.fromVersion(target))
        .load()
        .migrate();
  }

  private static void deployment(
      Statement sql, String id, String applicationId, String sha, String status, String at)
      throws Exception {
    sql.execute(
        "insert into cd_deployment (id, application_id, commit_sha, status, container_name,"
            + " created_at) values ('"
            + id
            + "', '"
            + applicationId
            + "', '"
            + sha
            + "', '"
            + status
            + "', 'container-"
            + id
            + "', timestamp with time zone '"
            + at
            + "')");
  }

  private static List<String> rows(Statement sql, String query) throws Exception {
    List<String> values = new ArrayList<>();
    try (ResultSet answered = sql.executeQuery(query)) {
      while (answered.next()) {
        values.add(answered.getString(1));
      }
    }
    return values;
  }
}
