package fi.refineid.signer.sign;

/** A document was not signed, and this says what stopped it. */
public class SigningFailedException extends Exception {

  private static final long serialVersionUID = 1L;

  public SigningFailedException(String message) {
    super(message);
  }

  public SigningFailedException(String message, Throwable cause) {
    super(message, cause);
  }
}
