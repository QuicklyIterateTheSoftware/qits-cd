-- The registry extraction, in the schema: a deployment stops pointing at a local application row.
--
-- Environments and applications are qits-serviceregistry's from this release on. cd keeps the
-- deployment history, and a deployment now names its application the way ci_run names its repo --
-- plain strings, no FK -- because the thing they name lives in another service's database. That
-- is also what keeps GET /cd/api/pins answering while the registry is down, which qits-artifacts'
-- image GC depends on (it deletes nothing when cd cannot answer).
--
-- cd_environment and cd_application are NOT dropped here. They are frozen: nothing reads or writes
-- them after the one-time export at startup, and dropping them is a separate cleanup migration once
-- the rollout has proven the export. A drop is the irreversible half of this change and does not
-- belong in the same release as the switch.
--
-- application_id is KEPT for the same reason, minus its FK and its NOT NULL: it is the only pointer
-- from a historical deployment back to the row it was written against, so it stays readable next to
-- the frozen tables until they go together.

alter table cd_deployment add column application_name varchar(64);
alter table cd_deployment add column environment_id varchar(255);

update cd_deployment set application_name =
    (select a.name from cd_application a where a.id = cd_deployment.application_id);

-- Nullable, and the null IS the statement: a singleton belongs to no tier, exactly as
-- cd_application.environment_id has said since V4. A STARTING row written by cd v1 keeps whatever
-- its application row said, so the startup sweep's adoption still finds the prior ACTIVE rows of
-- the same (application_name, environment_id) -- the pair that replaced "the same application_id".
update cd_deployment set environment_id =
    (select a.environment_id from cd_application a where a.id = cd_deployment.application_id);

alter table cd_deployment alter column application_name set not null;

alter table cd_deployment drop constraint if exists fk_cd_deployment_application;
alter table cd_deployment alter column application_id set null;

create index idx_cd_deployment_application_name on cd_deployment (application_name);
create index idx_cd_deployment_environment_id on cd_deployment (environment_id);

-- A monotonic tiebreak for the listings, and a real bug fix rather than a tidy-up.
--
-- Every deployment listing ordered by (created_at desc, id desc) and id is a random UUID, so two
-- rows recorded in the same tick -- which is what two deployments queued by one build-succeeded
-- event are -- came back in an arbitrary order that changed between calls. It was seen failing as
-- eachDeploymentCarriesTheRunOfTheBuildThatCausedIt, and a client reading "the first row per
-- application is the current one" would read the wrong one just as easily.
--
-- H2 fills an added AUTO_INCREMENT column for the rows already there and continues from the highest
-- value it assigned, so no sequence has to be restarted by hand. The MERGE then re-numbers the
-- backfilled rows into recorded order, because H2's fill follows table order and history should
-- read back the way it was written; it is a permutation of the same values, so the identity's next
-- value is still past all of them.
alter table cd_deployment add column seq bigint auto_increment;

merge into cd_deployment (id, seq) key(id)
    select id, row_number() over (order by created_at, id) from cd_deployment;
