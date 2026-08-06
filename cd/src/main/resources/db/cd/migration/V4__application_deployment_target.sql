-- Applications stop being environment-scoped by definition.
--
-- An environment is a TIER (dev, preprod, prod) and most applications still live in one. A few do
-- not: qits-idp and qits-cd are platform-plane, one instance for the whole host, and an environment
-- column that cannot be null forces them into a tier they do not belong to. So environment_id
-- becomes nullable and the null IS the statement: null environment <=> singleton.
--
-- deployment_target says the same thing in a word, so a reader never has to infer it from a null,
-- and so a query can filter on it. It is added WITH a default and the default is then dropped: the
-- default backfills every existing row in one statement (H2 rewrites the table), and dropping it
-- afterwards keeps the column a value every writer has to state -- the platform's worked pattern
-- for a not-null column on a populated table. available_on_env is backfilled the same way, false:
-- today exactly one application (qits-gateway) is a public node, and it declares itself one in its
-- own repository rather than being guessed at here.
--
-- branch is nullable and belongs to singletons only. An environment application takes its branch
-- from its environment (that is what an environment IS); a singleton has no environment to take one
-- from, so it carries its own -- `main` by convention, overridable in the repository's
-- .config/qits/deployments.yml.
--
-- uq_cd_application_env_name is deliberately KEPT as it is. With a null environment_id H2 treats
-- the rows as distinct, so it no longer constrains singletons at all; singleton name uniqueness is
-- enforced inside the service transaction that creates one, because a partial unique index is not
-- something H2 offers.

alter table cd_application alter column environment_id set null;

alter table cd_application add column deployment_target varchar(32) default 'ENVIRONMENT' not null;
alter table cd_application alter column deployment_target drop default;

alter table cd_application add column branch varchar(255);

alter table cd_application add column available_on_env boolean default false not null;
alter table cd_application alter column available_on_env drop default;

create index idx_cd_application_target on cd_application (deployment_target);
