package fi.refineid.signer.sign;

import eu.europa.esig.dss.asic.xades.ASiCWithXAdESSignatureParameters;
import eu.europa.esig.dss.asic.xades.signature.ASiCWithXAdESService;
import eu.europa.esig.dss.enumerations.ASiCContainerType;
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
import java.nio.file.Path;
import java.util.List;

/**
 * The card, asked for one signature at a time.
 *
 * <p>Every method here makes exactly one signature and therefore
 * costs exactly one PIN 2: the card verifies it per signature and
 * never caches it, and nothing in this application may reduce that
 * count (ADR-0007). A caller signing twelve documents separately calls
 * this twelve times and the holder answers twelve times.
 */
public final class CardSigner implements AutoCloseable {

  /** Where the modules live on macOS, most specific first. */
  public static final Path DEFAULT_MODULE =
      Path.of("/usr/local/lib/librefineid_pkcs11_sign.dylib");

  private final Pkcs11SignatureToken token;
  private final DSSPrivateKeyEntry key;

  private CardSigner(Pkcs11SignatureToken token, DSSPrivateKeyEntry key) {
    this.token = token;
    this.key = key;
  }

  /**
   * Opens the card through one PKCS#11 module and takes the key it
   * offers.
   *
   * <p>The signing module publishes one key, the one the card gates
   * behind PIN 2. A module offering more is refused rather than chosen
   * from: picking a signing key on a holder's behalf is how a document
   * gets signed with the key meant for logging in.
   */
  public static CardSigner open(Path module) throws SigningFailedException {
    Pkcs11SignatureToken token = null;
    try {
      // Selected by position in the slot list, not by slot number. The
      // module numbers slots as it finds identities and builds that
      // table only when the list is asked for, so a number reaches a
      // slot that does not exist yet and the card answers
      // CKR_SLOT_ID_INVALID. A negative slot id is how DSS is told to
      // use the index instead.
      token = new Pkcs11SignatureToken(
          module.toString(), (PasswordInputCallback) null, -1, 0, null);
      List<DSSPrivateKeyEntry> keys = token.getKeys();
      if (keys.size() != 1) {
        throw new SigningFailedException(
            "expected one signing key on the card, found " + keys.size());
      }
      return new CardSigner(token, keys.getFirst());
    } catch (SigningFailedException alreadyExplained) {
      closeQuietly(token);
      throw alreadyExplained;
    } catch (Exception failure) {
      closeQuietly(token);
      throw new SigningFailedException("the card could not be opened for signing", failure);
    }
  }

  /** Signs one PDF into itself, as PAdES. One PIN 2. */
  public void signPdf(Path document, Path output) throws SigningFailedException {
    try {
      DSSDocument toSign = new FileDocument(document.toFile());
      PAdESSignatureParameters parameters = new PAdESSignatureParameters();
      parameters.setSignatureLevel(SignatureLevel.PAdES_BASELINE_B);
      parameters.setDigestAlgorithm(DigestAlgorithm.SHA256);
      parameters.setSigningCertificate(key.getCertificate());
      parameters.setCertificateChain(key.getCertificateChain());

      PAdESService service = new PAdESService(new CommonCertificateVerifier());
      ToBeSigned dataToSign = service.getDataToSign(toSign, parameters);
      SignatureValue signature = token.sign(dataToSign, parameters.getDigestAlgorithm(), key);
      service.signDocument(toSign, parameters, signature).save(output.toString());
    } catch (Exception failure) {
      throw new SigningFailedException(document + " was not signed", failure);
    }
  }

  /**
   * Signs any number of documents into one ASiC-E container. One PIN 2
   * however many went in.
   */
  public void signContainer(List<Path> documents, Path output) throws SigningFailedException {
    try {
      List<DSSDocument> toSign = documents.stream()
          .map(path -> (DSSDocument) new FileDocument(path.toFile()))
          .toList();
      ASiCWithXAdESSignatureParameters parameters = new ASiCWithXAdESSignatureParameters();
      parameters.setSignatureLevel(SignatureLevel.XAdES_BASELINE_B);
      parameters.setDigestAlgorithm(DigestAlgorithm.SHA256);
      parameters.aSiC().setContainerType(ASiCContainerType.ASiC_E);
      parameters.setSigningCertificate(key.getCertificate());
      parameters.setCertificateChain(key.getCertificateChain());

      ASiCWithXAdESService service = new ASiCWithXAdESService(new CommonCertificateVerifier());
      ToBeSigned dataToSign = service.getDataToSign(toSign, parameters);
      SignatureValue signature = token.sign(dataToSign, parameters.getDigestAlgorithm(), key);
      service.signDocument(toSign, parameters, signature).save(output.toString());
    } catch (Exception failure) {
      throw new SigningFailedException("the container was not signed", failure);
    }
  }

  /** The certificate this will sign with, for a caller to show first. */
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
