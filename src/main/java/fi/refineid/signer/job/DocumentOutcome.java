package fi.refineid.signer.job;

import java.nio.file.Path;

/**
 * What became of one document.
 *
 * <p>A batch that reports only "done" hides which documents were
 * signed, and the holder finds out later, from someone else. Every
 * document leaves one of these behind.
 */
public sealed interface DocumentOutcome {

  /** The document this outcome is about. */
  Path source();

  /** Signed, and written here. */
  record Signed(Path source, Path output) implements DocumentOutcome {
  }

  /** Not signed, for this reason, said in words a person can act on. */
  record Failed(Path source, String reason) implements DocumentOutcome {
  }

  /**
   * Not attempted, because the holder stopped the job first.
   *
   * <p>Distinct from a failure: nothing went wrong with this document
   * and nothing was spent on it.
   */
  record Skipped(Path source) implements DocumentOutcome {
  }
}
