package fi.refineid.signer.job;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The name a signed document carries.
 *
 * <p>Held by a test because it is shared: a signed file made here has
 * to look like one made by the other ReFineID applications, and a
 * colon in it would not survive every file system it may cross.
 */
class SignedNameTest {

  private static final Instant NOON = Instant.parse("2026-08-09T12:34:56Z");

  @Test
  @DisplayName("the original name, the UTC instant, and the format's extension")
  void namesLikeTheRestOfReFineID() {
    assertEquals("contract - signed at 2026-08-09T12-34-56Z.pdf",
        SignedName.name(Path.of("/tmp/contract.pdf"), NOON, "pdf"));
  }

  @Test
  @DisplayName("no colons, whatever the instant")
  void carriesNoColons() {
    assertEquals(-1, SignedName.name(Path.of("a.pdf"), NOON, "pdf").indexOf(':'));
  }

  @Test
  @DisplayName("a name with dots keeps all but the last")
  void keepsInnerDots() {
    assertEquals("report.final - signed at 2026-08-09T12-34-56Z.pdf",
        SignedName.name(Path.of("report.final.pdf"), NOON, "pdf"));
  }

  @Test
  @DisplayName("a container is named .asice, and so is a non-PDF signed alone")
  void containerExtension() {
    assertEquals("asice", SignedName.extensionFor(Path.of("a.pdf"), JobShape.ONE_CONTAINER));
    assertEquals("asice", SignedName.extensionFor(Path.of("a.txt"), JobShape.EACH_DOCUMENT));
    assertEquals("pdf", SignedName.extensionFor(Path.of("a.pdf"), JobShape.EACH_DOCUMENT));
  }

  @Test
  @DisplayName("the signed file lands beside the original")
  void landsBeside() {
    assertEquals(Path.of("/tmp/x/contract - signed at 2026-08-09T12-34-56Z.pdf"),
        SignedName.beside(Path.of("/tmp/x/contract.pdf"), NOON, "pdf"));
  }
}
