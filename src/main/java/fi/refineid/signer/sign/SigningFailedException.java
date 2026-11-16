package fi.refineid.signer.sign;

import fi.refineid.signer.card.CardFailure;

/** A document was not signed, and this says what stopped it. */
public class SigningFailedException extends Exception {

  private static final long serialVersionUID = 1L;

  private final boolean pinFailure;

  public SigningFailedException(String message) {
    this(message, null, false);
  }

  public SigningFailedException(String message, Throwable cause) {
    this(message, cause, false);
  }

  public SigningFailedException(String message, Throwable cause, boolean pinFailure) {
    super(message, cause);
    this.pinFailure = pinFailure;
  }

  /**
   * Creates a failure caused by the card refusing or blocking PIN 2,
   * omitting any provider exception cause so raw PKCS#11 text cannot
   * surface to callers or logs.
   */
  public static SigningFailedException pinFailure(String message) {
    return new SigningFailedException(message, null, true);
  }

  /**
   * Translates a classified {@link CardFailure} into a signing failure
   * using {@code fallbackSentence} when the failure kind is {@code OTHER},
   * without attaching raw provider exception text.
   */
  public static SigningFailedException cardFailure(CardFailure failure, String fallbackSentence) {
    String message =
        failure.kind() == CardFailure.Kind.OTHER ? fallbackSentence : failure.sentence();
    return new SigningFailedException(message, null, failure.isPinFailure());
  }

  /**
   * Whether the card refused or blocked the PIN, meaning a running
   * batch must stop immediately rather than spend another retry.
   */
  public boolean isPinFailure() {
    return pinFailure;
  }
}
