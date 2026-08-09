package fi.refineid.signer.card;

/**
 * The card could not be reached, and this says why in words a person
 * can act on.
 *
 * <p>A missing module, a reader with nothing in it, and a card that
 * refused are three different problems with three different remedies,
 * and a signing application that reports them all as "error" sends its
 * holder to look in the wrong place. The message is the remedy; the
 * cause, when there is one, is kept for the diagnostic report and is
 * never shown as the whole explanation.
 */
public class CardUnavailableException extends Exception {

  private static final long serialVersionUID = 1L;

  public CardUnavailableException(String message) {
    super(message);
  }

  public CardUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
