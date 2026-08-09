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
 * @param pin whether the holder types PIN 2 once or for every signature
 */
public record JobPlan(List<Path> documents, JobShape shape, PinPolicy pin) {

  public JobPlan {
    documents = List.copyOf(documents);
    if (documents.isEmpty()) {
      throw new IllegalArgumentException("a signing job needs at least one document");
    }
  }

  /**
   * How many signatures the card will make, and therefore how many
   * times it verifies PIN 2 -- which is not the same as how many times
   * anyone types it.
   */
  public int signatureCount() {
    return switch (shape) {
      case ONE_CONTAINER -> 1;
      case EACH_DOCUMENT -> documents.size();
    };
  }

  /**
   * How many times the holder types PIN 2, which is the number that
   * belongs in front of them.
   */
  public int pinEntries() {
    return pin == PinPolicy.ASK_ONCE_FOR_THE_JOB ? 1 : signatureCount();
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
    String work = switch (shape) {
      case ONE_CONTAINER -> documentCount + " in one signed container";
      case EACH_DOCUMENT -> documentCount + " signed separately";
    };
    return work + "; you will be asked for " + promptCount;
  }
}
