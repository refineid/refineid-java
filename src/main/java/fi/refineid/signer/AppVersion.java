package fi.refineid.signer;

/**
 * Which build this is.
 *
 * <p>Read from the jar manifest rather than written into the source, so
 * one stamp reaches the artifact, the bundle and the window without
 * three places to forget. A run from a class directory has no manifest
 * and says so instead of inventing a number.
 */
public final class AppVersion {

  /** What a build that was never packaged reports. */
  public static final String UNPACKAGED = "development build";

  private AppVersion() {
  }

  /** The full stamp, including the ten-minute bucket. */
  public static String current() {
    String version = AppVersion.class.getPackage().getImplementationVersion();
    return version == null || version.isBlank() ? UNPACKAGED : version;
  }
}
