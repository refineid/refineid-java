package fi.refineid.signer.job;

import java.nio.file.Path;
import java.util.List;

/**
 * What a job will do, before it does any of it.
 *
 * <p>The number that matters is how many times the card will ask for
 * PIN 2, and it is stated first because it is the one thing a holder
 * cannot discover by looking. Someone expecting a single prompt who
 * meets twelve concludes the software is broken; someone told to
 * expect twelve answers twelve.
 *
 * @param documents what will be signed, in the order it will be signed
 * @param shape whether this is one signature or one per document
 */
public record JobPlan(List<Path> documents, JobShape shape) {

  public JobPlan {
    documents = List.copyOf(documents);
    if (documents.isEmpty()) {
      throw new IllegalArgumentException("a signing job needs at least one document");
    }
  }

  /**
   * How many signatures the card will make, which is also how many
   * times it will ask for PIN 2: the card verifies it per signature
   * and never caches it (ADR-0007).
   */
  public int signatureCount() {
    return switch (shape) {
      case ONE_CONTAINER -> 1;
      case EACH_DOCUMENT -> documents.size();
    };
  }

  /** The same number under the name the holder cares about. */
  public int pinEntries() {
    return signatureCount();
  }

  /** One sentence to put in front of a holder before starting. */
  public String summary() {
    int prompts = pinEntries();
    String documentCount = documents.size() == 1
        ? "1 document"
        : documents.size() + " documents";
    String promptCount = switch (prompts) {
      case 1 -> "PIN 2 once";
      case 2 -> "PIN 2 twice";
      default -> "PIN 2 " + prompts + " times";
    };
    return switch (shape) {
      case ONE_CONTAINER ->
          documentCount + " in one signed container; the card will ask for " + promptCount;
      case EACH_DOCUMENT ->
          documentCount + " signed separately; the card will ask for " + promptCount;
    };
  }
}
