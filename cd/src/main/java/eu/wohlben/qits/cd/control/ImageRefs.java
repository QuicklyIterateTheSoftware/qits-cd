package eu.wohlben.qits.cd.control;

/**
 * The image-reference convention — the one place it is spelled: {@code
 * <registry-host>/<repository>/<application>:<sha>}, e.g. {@code
 * qits-artifacts:8080/qits/qits-gateway:4f2a91c...}.
 *
 * <p>Nothing in the build notification names an image; cd <em>derives</em> the reference from the
 * application's name and the commit sha, which makes the tag convention a contract the publisher
 * has to meet rather than a value someone forgot to send. The registry host here is the one the
 * <b>docker daemon</b> resolves (it does the pulling), not one this process dials.
 */
public final class ImageRefs {

  private ImageRefs() {}

  public static String imageRef(
      String registryHost, String imageRepository, String applicationName, String sha) {
    return registryHost + "/" + imageRepository + "/" + applicationName + ":" + sha;
  }
}
