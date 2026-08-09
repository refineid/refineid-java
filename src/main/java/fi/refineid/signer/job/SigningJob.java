package fi.refineid.signer.job;

import fi.refineid.signer.sign.CardSigner;
import fi.refineid.signer.sign.SigningFailedException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A set of documents, signed as one job.
 *
 * <p>Dropping twelve files starts one job and not twelve, but it does
 * not make one signature out of twelve: what the shape decides is
 * whether the holder authorizes once, over a container, or once per
 * document (ADR-0007).
 *
 * <p>A job runs to the end. A document that fails does not discard the
 * ones already signed and does not stop the ones after it, because a
 * batch that abandons eleven good signatures over one bad file has
 * spent eleven PIN entries for nothing.
 */
public final class SigningJob {

  /** Watches a job as it goes, so a window can follow it. */
  public interface Progress {

    /** About to ask the card, and therefore the holder, for document n of m. */
    void starting(Path document, int number, int total);

    /** What became of it. */
    void finished(DocumentOutcome outcome);

    /**
     * Whether the holder has asked to stop.
     *
     * <p>Consulted between documents only: a signature already begun
     * is finished, because the PIN was already spent on it.
     */
    boolean cancelled();
  }

  private final CardSigner signer;
  private final Path destination;

  public SigningJob(CardSigner signer, Path destination) {
    this.signer = signer;
    this.destination = destination;
  }

  /** Runs the plan and reports what became of every document. */
  public List<DocumentOutcome> run(JobPlan plan, Progress progress) {
    return switch (plan.shape()) {
      case ONE_CONTAINER -> runAsContainer(plan, progress);
      case EACH_DOCUMENT -> runSeparately(plan, progress);
    };
  }

  /** One signature over everything: the card asks once. */
  private List<DocumentOutcome> runAsContainer(JobPlan plan, Progress progress) {
    Path container = destination.resolve(
        SignedName.name(plan.documents().getFirst(), Instant.now(), "asice"));
    Path first = plan.documents().getFirst();
    progress.starting(first, 1, 1);
    try {
      signer.signContainer(plan.documents(), container);
      List<DocumentOutcome> outcomes = plan.documents().stream()
          .map(document -> (DocumentOutcome) new DocumentOutcome.Signed(document, container))
          .toList();
      outcomes.forEach(progress::finished);
      return outcomes;
    } catch (SigningFailedException failure) {
      // One signature failing takes the whole container with it: there
      // is no partly signed container, and saying otherwise per
      // document would be a lie about what happened.
      List<DocumentOutcome> outcomes = plan.documents().stream()
          .map(document ->
              (DocumentOutcome) new DocumentOutcome.Failed(document, reason(failure)))
          .toList();
      outcomes.forEach(progress::finished);
      return outcomes;
    }
  }

  /** One signature each: the card asks once per document. */
  private List<DocumentOutcome> runSeparately(JobPlan plan, Progress progress) {
    List<DocumentOutcome> outcomes = new ArrayList<>();
    List<Path> documents = plan.documents();
    for (int index = 0; index < documents.size(); index++) {
      Path document = documents.get(index);
      if (progress.cancelled()) {
        DocumentOutcome skipped = new DocumentOutcome.Skipped(document);
        outcomes.add(skipped);
        progress.finished(skipped);
        continue;
      }
      progress.starting(document, index + 1, documents.size());
      outcomes.add(signOne(document, progress));
    }
    return outcomes;
  }

  private DocumentOutcome signOne(Path document, Progress progress) {
    DocumentOutcome outcome;
    try {
      Instant signedAt = Instant.now();
      if (isPdf(document)) {
        Path output = destination.resolve(SignedName.name(document, signedAt, "pdf"));
        signer.signPdf(document, output);
        outcome = new DocumentOutcome.Signed(document, output);
      } else {
        // Only a PDF can carry a PAdES signature. Anything else keeps
        // its own container rather than being quietly left unsigned.
        Path output = destination.resolve(SignedName.name(document, signedAt, "asice"));
        signer.signContainer(List.of(document), output);
        outcome = new DocumentOutcome.Signed(document, output);
      }
    } catch (SigningFailedException failure) {
      outcome = new DocumentOutcome.Failed(document, reason(failure));
    }
    progress.finished(outcome);
    return outcome;
  }

  private static boolean isPdf(Path document) {
    return document.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
  }



  /** The failure as one sentence, with the cause when it adds anything. */
  private static String reason(SigningFailedException failure) {
    Throwable cause = failure.getCause();
    if (cause == null || cause.getMessage() == null) {
      return failure.getMessage();
    }
    return failure.getMessage() + ": " + cause.getMessage();
  }
}
