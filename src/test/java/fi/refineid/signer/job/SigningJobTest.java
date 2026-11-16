// Copyright 2026 Petri Koistinen
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package fi.refineid.signer.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import fi.refineid.signer.sign.DocumentSigner;
import fi.refineid.signer.sign.SigningFailedException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SigningJobTest {

  private static final List<Path> THREE_PDFS =
      List.of(Path.of("first.pdf"), Path.of("second.pdf"), Path.of("third.pdf"));

  private static final class RecordingProgress implements SigningJob.Progress {
    final List<Path> started = new ArrayList<>();
    final List<DocumentOutcome> finished = new ArrayList<>();
    boolean cancelled;

    @Override
    public void starting(Path document, int number, int total) {
      started.add(document);
    }

    @Override
    public void finished(DocumentOutcome outcome) {
      finished.add(outcome);
    }

    @Override
    public boolean cancelled() {
      return cancelled;
    }
  }

  @Test
  @DisplayName("a PIN refusal stops the batch immediately and skips remaining documents")
  void pinRefusalStopsBatchImmediately() {
    AtomicInteger calls = new AtomicInteger();
    DocumentSigner signer =
        new DocumentSigner() {
          @Override
          public void signPdf(Path document, Path output) throws SigningFailedException {
            calls.incrementAndGet();
            throw SigningFailedException.pinFailure("The card refused that PIN");
          }

          @Override
          public void signContainer(List<Path> documents, Path output) {
            calls.incrementAndGet();
          }
        };

    JobPlan plan =
        new JobPlan(THREE_PDFS, JobShape.EACH_DOCUMENT, PinPolicy.ASK_ONCE_FOR_THE_JOB);
    RecordingProgress progress = new RecordingProgress();
    List<DocumentOutcome> outcomes = new SigningJob(signer, Path.of("out")).run(plan, progress);

    assertEquals(1, calls.get(), "must not present a rejected PIN for later documents");
    assertEquals(List.of(Path.of("first.pdf")), progress.started);
    assertEquals(3, outcomes.size());
    assertEquals(outcomes, progress.finished);

    DocumentOutcome.Failed first = assertInstanceOf(DocumentOutcome.Failed.class, outcomes.get(0));
    assertEquals("The card refused that PIN", first.reason());
    assertInstanceOf(DocumentOutcome.Skipped.class, outcomes.get(1));
    assertInstanceOf(DocumentOutcome.Skipped.class, outcomes.get(2));
  }

  @Test
  @DisplayName("a PIN lock mid-batch preserves earlier signed files and skips later ones")
  void pinLockMidBatchPreservesEarlierAndSkipsLater() {
    AtomicInteger calls = new AtomicInteger();
    DocumentSigner signer =
        new DocumentSigner() {
          @Override
          public void signPdf(Path document, Path output) throws SigningFailedException {
            if (calls.incrementAndGet() == 2) {
              throw SigningFailedException.pinFailure("The card has blocked that PIN");
            }
          }

          @Override
          public void signContainer(List<Path> documents, Path output) {}
        };

    JobPlan plan = new JobPlan(THREE_PDFS, JobShape.EACH_DOCUMENT, PinPolicy.ASK_EACH_SIGNATURE);
    RecordingProgress progress = new RecordingProgress();
    List<DocumentOutcome> outcomes = new SigningJob(signer, Path.of("out")).run(plan, progress);

    assertEquals(2, calls.get());
    assertInstanceOf(DocumentOutcome.Signed.class, outcomes.get(0));
    DocumentOutcome.Failed second =
        assertInstanceOf(DocumentOutcome.Failed.class, outcomes.get(1));
    assertEquals("The card has blocked that PIN", second.reason());
    assertInstanceOf(DocumentOutcome.Skipped.class, outcomes.get(2));
  }

  @Test
  @DisplayName("a non-PIN document failure continues signing the remaining documents")
  void nonPinFailureContinuesBatch() {
    AtomicInteger calls = new AtomicInteger();
    DocumentSigner signer =
        new DocumentSigner() {
          @Override
          public void signPdf(Path document, Path output) throws SigningFailedException {
            if (calls.incrementAndGet() == 1) {
              throw new SigningFailedException(
                  document + " was not signed", new IOException("corrupt xref"));
            }
          }

          @Override
          public void signContainer(List<Path> documents, Path output) {}
        };

    JobPlan plan =
        new JobPlan(THREE_PDFS, JobShape.EACH_DOCUMENT, PinPolicy.ASK_ONCE_FOR_THE_JOB);
    RecordingProgress progress = new RecordingProgress();
    List<DocumentOutcome> outcomes = new SigningJob(signer, Path.of("out")).run(plan, progress);

    assertEquals(3, calls.get());
    DocumentOutcome.Failed first = assertInstanceOf(DocumentOutcome.Failed.class, outcomes.get(0));
    assertEquals("first.pdf was not signed: corrupt xref", first.reason());
    assertInstanceOf(DocumentOutcome.Signed.class, outcomes.get(1));
    assertInstanceOf(DocumentOutcome.Signed.class, outcomes.get(2));
  }
}
