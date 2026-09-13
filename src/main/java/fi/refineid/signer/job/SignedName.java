package fi.refineid.signer.job;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * What a signed document is called.
 *
 * <p>The same name the rest of RefineID gives it, so a signed file
 * looks the same whichever application made it: the original name, the
 * instant it was signed, and the extension the format calls for.
 *
 * <p>The instant is UTC and written in ISO 8601 with its colons
 * replaced, because a colon is not safe in a file name everywhere the
 * file may travel.
 */
public final class SignedName {

  private static final DateTimeFormatter INSTANT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

  private SignedName() {
  }

  /**
   * The signed file's place beside the original.
   *
   * @param source the document being signed, whose name and folder are kept
   * @param signedAt when it was signed
   * @param extension the extension the format calls for, without a dot
   */
  public static Path beside(Path source, Instant signedAt, String extension) {
    return source.resolveSibling(name(source, signedAt, extension));
  }

  /** The signed file's name on its own. */
  public static String name(Path source, Instant signedAt, String extension) {
    String fileName = source.getFileName().toString();
    int dot = fileName.lastIndexOf('.');
    String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
    return stem + " - signed at " + INSTANT.format(signedAt) + "." + extension;
  }

  /**
   * The extension a signed document keeps.
   *
   * <p>A PDF signed as PAdES stays a PDF and carries the signature
   * inside it. Everything else is inside a container, and the
   * container is what gets a name.
   */
  public static String extensionFor(Path source, JobShape shape) {
    if (shape == JobShape.ONE_CONTAINER || !isPdf(source)) {
      return "asice";
    }
    return "pdf";
  }

  private static boolean isPdf(Path document) {
    return document.getFileName().toString()
        .toLowerCase(java.util.Locale.ROOT).endsWith(".pdf");
  }
}
