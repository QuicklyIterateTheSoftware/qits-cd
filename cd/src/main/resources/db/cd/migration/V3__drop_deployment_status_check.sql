-- H2 2.4.240 ties a checked IN-set to the session that compiled it. After that session is retired,
-- a later insert fails with 23514 "Check constraint invalid" despite a valid enum value. A clean
-- bootstrap observed this on qits-cd's ninth deployment. DeploymentStatus already owns validity at
-- every Java write path, so remove the duplicated database enum check.
alter table cd_deployment drop constraint if exists constraint_48;
