package fi.refineid.signer.sign;

import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureLevel;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.FileDocument;
import eu.europa.esig.dss.model.SignatureValue;
import eu.europa.esig.dss.model.ToBeSigned;
import eu.europa.esig.dss.pades.PAdESSignatureParameters;
import eu.europa.esig.dss.pades.signature.PAdESService;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.token.DSSPrivateKeyEntry;
import eu.europa.esig.dss.token.PasswordInputCallback;
import eu.europa.esig.dss.token.Pkcs11SignatureToken;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * One PDF, signed with the card.
 *
 * <p>The card is asked for exactly one signature here, and the holder
 * authorizes exactly one: PIN 2 is verified per signature and never
 * cached, so a caller signing several documents calls this several
 * times and the holder answers several times. That count is not
 * something this class may reduce (ADR-0007).
 */
public final class PadesSigner implements AutoCloseable {

  private final Pkcs11SignatureToken token;
  private final DSSPrivateKeyEntry key;

  private PadesSigner(Pkcs11SignatureToken token, DSSPrivateKeyEntry key) {
    this.token = token;
    this.key = key;
  }

  /**
   * Opens the card through one PKCS#11 module and takes the key it
   * offers.
   *
   * <p>The signing module publishes one key, the one the card gates
   * behind PIN 2. If a module ever offers more, this refuses rather
   * than choosing: picking a signing key on a holder's behalf is how a
   * document ends up signed with the key meant for logging in.
   */
  public static PadesSigner open(Path module) throws SigningFailedException {
    Pkcs11SignatureToken token = null;
    try {
      // Selected by position in the slot list, not by slot number.
      // The module numbers slots as it finds identities and only
      // builds that table when the list is asked for, so naming a
      // number reaches a slot that does not exist yet and the card
      // answers CKR_SLOT_ID_INVALID. A negative slot id is how DSS is
      // told to use the index instead.
      token = new Pkcs11SignatureToken(
          module.toString(), (PasswordInputCallback) null, -1, 0, null);
      List<DSSPrivateKeyEntry> keys = token.getKeys();
      if (keys.size() != 1) {
        throw new SigningFailedException(
            "expected one signing key on the card, found " + keys.size());
      }
      return new PadesSigner(token, keys.getFirst());
    } catch (SigningFailedException alreadyExplained) {
      closeQuietly(token);
      throw alreadyExplained;
    } catch (Exception failure) {
      closeQuietly(token);
      throw new SigningFailedException("the card could not be opened for signing", failure);
    }
  }

  /**
   * Signs {@code document} and writes the result to {@code output}.
   *
   * <p>The card is asked once, between the two DSS calls: everything
   * before {@code getDataToSign} and after {@code signDocument} is
   * arithmetic on this machine.
   */
  public void sign(Path document, Path output) throws SigningFailedException {
    try {
      DSSDocument toSign = new FileDocument(document.toFile());
      PAdESSignatureParameters parameters = new PAdESSignatureParameters();
      parameters.setSignatureLevel(SignatureLevel.PAdES_BASELINE_B);
      parameters.setDigestAlgorithm(DigestAlgorithm.SHA256);
      parameters.setSigningCertificate(key.getCertificate());
      parameters.setCertificateChain(key.getCertificateChain());

      PAdESService service = new PAdESService(new CommonCertificateVerifier());
      ToBeSigned dataToSign = service.getDataToSign(toSign, parameters);
      SignatureValue signature =
          token.sign(dataToSign, parameters.getDigestAlgorithm(), key);
      DSSDocument signed = service.signDocument(toSign, parameters, signature);
      signed.save(output.toString());
    } catch (IOException writeFailed) {
      throw new SigningFailedException("the signed document could not be written", writeFailed);
    } catch (Exception failure) {
      throw new SigningFailedException("the card did not sign this document", failure);
    }
  }

  /** The certificate the signature will name, for a caller to show. */
  public String signerName() {
    return key.getCertificate().getSubject().getPrettyPrintRFC2253();
  }

  private static void closeQuietly(Pkcs11SignatureToken token) {
    if (token != null) {
      token.close();
    }
  }

  @Override
  public void close() {
    token.close();
  }
}
