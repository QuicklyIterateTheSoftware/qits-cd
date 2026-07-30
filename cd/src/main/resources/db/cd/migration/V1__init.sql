-- The CD schema: environments (one per epic, by convention), the applications each tracks, and the
-- recorded deployments. repo_id is a plain string — NO FK into any other context's tables
-- (separate physical DB, the ci/artifacts stance); the FKs below are inside cd's own DB, which is
-- fine.

create table cd_environment (
    id varchar(255) not null primary key,
    name varchar(64) not null,
    branch varchar(255) not null,
    network varchar(255) not null,
    created_at timestamp(6) with time zone not null
);

alter table cd_environment add constraint uq_cd_environment_name unique (name);
create index idx_cd_environment_branch on cd_environment (branch);

create table cd_application (
    id varchar(255) not null primary key,
    environment_id varchar(255) not null,
    repo_id varchar(255) not null,
    name varchar(64) not null,
    health_path varchar(255),
    created_at timestamp(6) with time zone not null
);

alter table cd_application add constraint fk_cd_application_environment
    foreign key (environment_id) references cd_environment;
alter table cd_application add constraint uq_cd_application_env_name unique (environment_id, name);
create index idx_cd_application_repo_id on cd_application (repo_id);

create table cd_deployment (
    id varchar(255) not null primary key,
    application_id varchar(255) not null,
    commit_sha varchar(64) not null,
    status varchar(32) not null check (status in
        ('QUEUED', 'STARTING', 'ACTIVE', 'IMAGE_MISSING', 'FAILED', 'DECOMMISSIONED')),
    container_name varchar(255),
    detail clob,
    created_at timestamp(6) with time zone not null,
    finished_at timestamp(6) with time zone
);

alter table cd_deployment add constraint fk_cd_deployment_application
    foreign key (application_id) references cd_application;
create index idx_cd_deployment_application_id on cd_deployment (application_id);
create index idx_cd_deployment_created_at on cd_deployment (created_at);
