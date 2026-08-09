package fi.refineid.signer.card;

/**
 * Whether the card's certificate is still worth signing with.
 *
 * <p>A revoked certificate signs perfectly and validates never. The
 * document that comes out is correct in every other respect --
 * format, cryptography, a qualified timestamp, a qualified certificate
 * in a qualified device -- and a validator still refuses it, because
 * the credential was withdrawn before the signature was made. Making
 * one costs a PIN entry and produces a file nobody can use.
 *
 * <p>So the status is read before signing rather than discovered
 * afterwards by whoever was sent the document.
 */
public record CredentialStatus(State state, String detail) {

  /** What the issuer says about the certificate now. */
  public enum State {

    /** The issuer answered, and the certificate stands. */
    USABLE,

    /** The issuer answered, and the certificate is withdrawn. */
    REVOKED,

    /**
     * The issuer could not be asked.
     *
     * <p>Not a refusal. A signing application that stops working
     * because a revocation service is unreachable is worse than one
     * that signs and says it could not check; the signature may be
     * perfectly good, and the holder is told what is unknown about it.
     */
    UNKNOWN
  }

  /** Whether signing should go ahead. */
  public boolean permitsSigning() {
    return state != State.REVOKED;
  }

  /** One sentence for a window or a report. */
  public String sentence() {
    return switch (state) {
      case USABLE -> "Certificate valid";
      case REVOKED -> "Certificate revoked" + (detail.isBlank() ? "" : " -- " + detail);
      case UNKNOWN -> "Certificate not checked" + (detail.isBlank() ? "" : " -- " + detail);
    };
  }
}
