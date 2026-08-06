package eu.wohlben.qits.cd.entity;

/**
 * Where an application runs: once per environment, or once for the whole platform.
 *
 * <p>The distinction is not a size but a plane. An {@link #ENVIRONMENT} application is part of a
 * tier — dev, preprod, prod each get their own copy, deployed from that tier's branch, isolated on
 * that tier's networks. A {@link #SINGLETON} is platform-plane: one instance serves every
 * environment, deploys from its own branch (`main` by convention), and is reachable from every
 * environment's networks by design. qits-idp is the only singleton deployed today — an identity
 * provider each tier mints its own tokens from would be a different platform; the planned
 * qits-serviceregistry joins it when that leg lands. qits-cd is not one: it is an ordinary
 * environment application, so every tier runs its own deployer.
 *
 * <p>A repository declares this in its {@code .config/qits/deployments.yml}; cd derives the
 * application row from it on every green build.
 */
public enum CdDeploymentTarget {

  /** One instance per environment, in the environment's networks, from the environment's branch. */
  ENVIRONMENT,

  /** One instance for the whole platform, in every environment's networks, from its own branch. */
  SINGLETON
}
