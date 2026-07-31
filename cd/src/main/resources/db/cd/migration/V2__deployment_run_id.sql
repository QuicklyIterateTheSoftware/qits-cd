-- The qits-ci run whose green build caused this deployment. The intake has always RECEIVED runId --
-- qits-ci's CdBuildNotifier posts {runId, repoId, branch, commitSha} -- and until now dropped it on
-- the floor; this column is where it lands. It is the one thing that makes the deployment -> its
-- build click-through possible from the cd explorer (/ci/runs/<runId>), which is the screen that
-- earns the column.
--
-- No FK and no index: this is another context's identifier, the same stance repo_id already takes
-- (separate physical DB), and nothing here queries by it -- the deployment rows are always read
-- through their environment.
--
-- Nullable, and deliberately NOT backfilled: nothing on an existing row can derive the run that
-- caused it, so every deployment recorded before this migration reads null and says so. The same
-- goes for the deployment that rolls this column out -- qits-cd deploying itself is queued by the
-- predecessor, which had no column to write.

alter table cd_deployment add column run_id varchar(255);
