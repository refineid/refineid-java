package fi.refineid.signer.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The count a holder is shown before a job starts.
 *
 * <p>This is the number that decides whether the software looks broken:
 * someone told to expect one prompt and met with twelve stops trusting
 * it. The arithmetic is therefore held by a test rather than by
 * whoever last edited the window.
 */
class JobPlanTest {

  private static final List<Path> THREE = List.of(
      Path.of("contract.pdf"), Path.of("appendix.pdf"), Path.of("terms.pdf"));

  @Test
  @DisplayName("a container over many documents is one signature and one PIN 2")
  void containerAsksOnce() {
    JobPlan plan = new JobPlan(THREE, JobShape.ONE_CONTAINER);
    assertEquals(1, plan.signatureCount());
    assertEquals(1, plan.pinEntries());
  }

  @Test
  @DisplayName("documents signed separately cost one PIN 2 each")
  void separateAsksPerDocument() {
    JobPlan plan = new JobPlan(THREE, JobShape.EACH_DOCUMENT);
    assertEquals(3, plan.signatureCount());
    assertEquals(3, plan.pinEntries());
  }

  @Test
  @DisplayName("the summary says the number of prompts, not just the number of files")
  void summaryNamesThePrompts() {
    assertTrue(new JobPlan(THREE, JobShape.EACH_DOCUMENT).summary().contains("3 times"));
    assertTrue(new JobPlan(THREE, JobShape.ONE_CONTAINER).summary().contains("once"));
  }

  @Test
  @DisplayName("one document reads as one document, not as 1 documents")
  void singularReadsProperly() {
    JobPlan plan = new JobPlan(List.of(Path.of("contract.pdf")), JobShape.EACH_DOCUMENT);
    assertFalse(plan.summary().contains("1 documents"), plan.summary());
    assertTrue(plan.summary().contains("1 document "), plan.summary());
    assertTrue(plan.summary().contains("once"), plan.summary());
  }

  @Test
  @DisplayName("two prompts read as twice, not as PIN 2 2 times")
  void twoReadsProperly() {
    JobPlan plan = new JobPlan(THREE.subList(0, 2), JobShape.EACH_DOCUMENT);
    assertTrue(plan.summary().contains("twice"), plan.summary());
  }

  @Test
  @DisplayName("a job with nothing in it is refused rather than run")
  void emptyJobRefused() {
    assertThrows(
        IllegalArgumentException.class, () -> new JobPlan(List.of(), JobShape.EACH_DOCUMENT));
  }
}
