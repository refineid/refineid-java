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
import eu.europa.esig.dss.service.ocsp.OnlineOCSPSource;
import eu.europa.esig.dss.service.http.commons.TimestampDataLoader;
import eu.europa.esig.dss.service.tsp.OnlineTSPSource;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.model.x509.revocation.ocsp.OCSP;
import eu.europa.esig.dss.spi.x509.aia.DefaultAIASource;
import eu.europa.esig.dss.spi.x509.revocation.RevocationToken;
import fi.refineid.signer.card.CredentialStatus;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.token.DSSPrivateKeyEntry;
import eu.europa.esig.dss.token.PasswordInputCallback;
import eu.europa.esig.dss.token.Pkcs11SignatureToken;
import java.nio.file.Path;
import java.util.Locale;
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

  /** Where the signing module is installed on macOS. */
  public static final Path INSTALLED_MODULE =
      Path.of("/usr/local/lib/librefineid_pkcs11_sign.dylib");

  /** Names a module somewhere else, for a build under test. */
  public static final String MODULE_PROPERTY = "refineid.module";

  /**
   * Which slot of that module to open, counted along the module's own
   * list.
   *
   * <p>Modules disagree about what a slot is. The ReFineID module
   * publishes one per identity and keeps the signing key in a module
   * of its own, so the first slot is the right one. Atostek's module
   * publishes two, labelled for PIN 1 and PIN 2, and the signing key
   * is behind the second. An application that is not tied to one
   * vendor cannot assume either.
   */
  public static final String SLOT_PROPERTY = "refineid.slot";

  /**
   * Which cryptosystem to sign with when the card carries more than
   * one signing key, as this one does.
   */
  public static final String KEY_PROPERTY = "refineid.key";

  /**
   * The module this run will use: the installed one unless another was
   * named.
   */
  public static Path defaultModule() {
    String named = System.getProperty(MODULE_PROPERTY);
    return named == null ? INSTALLED_MODULE : Path.of(named);
  }

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
    return open(module, null);
  }

  /**
   * Opens the card with the PIN supplied by the caller instead of by
   * the system dialog.
   *
   * <p>For a session that cannot answer a dialog -- headless, remote,
   * a test -- where the module is run with textual PIN entry and
   * withdraws its protected-authentication-path flag. The value is
   * held only for the call that hands it to the module, which passes
   * it to the card as an authentication context; nothing here stores
   * it, writes it, or puts it in a message.
   */
  public static CardSigner open(Path module, char[] pin) throws SigningFailedException {
    Pkcs11SignatureToken token = null;
    try {
      // Selected by position in the slot list, not by slot number. The
      // module numbers slots as it finds identities and builds that
      // table only when the list is asked for, so a number reaches a
      // slot that does not exist yet and the card answers
      // CKR_SLOT_ID_INVALID. A negative slot id is how DSS is told to
      // use the index instead.
      PasswordInputCallback password = pin == null ? null : () -> pin;
      token = new Pkcs11SignatureToken(module.toString(), password, -1, slotIndex(), null);
      return new CardSigner(token, signingKey(token.getKeys()));
    } catch (SigningFailedException alreadyExplained) {
      closeQuietly(token);
      throw alreadyExplained;
    } catch (Exception failure) {
      closeQuietly(token);
      throw new SigningFailedException(reason(module, failure), failure);
    }
  }

  /**
   * What went wrong, said as the thing to do about it.
   *
   * <p>"The card could not be opened" is true of an empty reader, an
   * older module, and a card that refused, and sends the holder to
   * look in three different places.
   */
  private static String reason(Path module, Exception failure) {
    String detail = chain(failure).toLowerCase(Locale.ROOT);
    if (detail.contains("has 0 slots") || detail.contains("token not present")
        || detail.contains("no such slot")) {
      return "no card in the reader";
    }
    if (detail.contains("ckr_attribute_type_invalid")) {
      return "the card module at " + module
          + " is too old for this application: it does not publish CKA_EXTRACTABLE, "
          + "which Java requires before it will open a private key";
    }
    if (detail.contains("ckr_pin_incorrect") || detail.contains("pin")) {
      return "the card refused the PIN";
    }
    return "the card could not be opened through " + module + ": " + chain(failure);
  }

  /** Every message in the cause chain, which is where the reason hides. */
  private static String chain(Throwable failure) {
    StringBuilder text = new StringBuilder();
    for (Throwable step = failure; step != null; step = step.getCause()) {
      if (step.getMessage() != null) {
        text.append(text.isEmpty() ? "" : "; ").append(step.getMessage());
      }
    }
    return text.toString();
  }

  /** Signs one PDF into itself, as PAdES. One PIN 2. */
  public void signPdf(Path document, Path output) throws SigningFailedException {
    try {
      DSSDocument toSign = new FileDocument(document.toFile());
      PAdESSignatureParameters parameters = new PAdESSignatureParameters();
      parameters.setSignatureLevel(SignatureLevel.PAdES_BASELINE_T);
      parameters.setDigestAlgorithm(DigestAlgorithm.SHA256);
      parameters.setSigningCertificate(key.getCertificate());
      parameters.setCertificateChain(key.getCertificateChain());

      PAdESService service = new PAdESService(new CommonCertificateVerifier());
      service.setTspSource(timestamps());
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
      parameters.setSignatureLevel(SignatureLevel.XAdES_BASELINE_T);
      parameters.setDigestAlgorithm(DigestAlgorithm.SHA256);
      parameters.aSiC().setContainerType(ASiCContainerType.ASiC_E);
      parameters.setSigningCertificate(key.getCertificate());
      parameters.setCertificateChain(key.getCertificateChain());

      ASiCWithXAdESService service = new ASiCWithXAdESService(new CommonCertificateVerifier());
      service.setTspSource(timestamps());
      ToBeSigned dataToSign = service.getDataToSign(toSign, parameters);
      SignatureValue signature = token.sign(dataToSign, parameters.getDigestAlgorithm(), key);
      service.signDocument(toSign, parameters, signature).save(output.toString());
    } catch (Exception failure) {
      throw new SigningFailedException("the container was not signed", failure);
    }
  }

  /**
   * The key a document is signed with, chosen from what the slot
   * offers.
   *
   * <p>Only a key whose certificate carries non-repudiation may sign a
   * document: that bit is what separates a signing credential from the
   * one used to log in, and choosing wrongly signs a contract with a
   * login key.
   *
   * <p>A card may carry more than one such key. This one carries two,
   * an elliptic-curve and an RSA certificate for the same person, and
   * both are legitimate. The curve is preferred because it is the
   * current enrollment on these cards, and the choice is reported so
   * the holder can see which signed.
   */
  private static DSSPrivateKeyEntry signingKey(List<DSSPrivateKeyEntry> offered)
      throws SigningFailedException {
    List<DSSPrivateKeyEntry> committing = offered.stream()
        .filter(CardSigner::commitsContent)
        .toList();
    if (committing.isEmpty()) {
      throw new SigningFailedException(
          offered.isEmpty()
              ? "the card offered no keys"
              : "the card offered no key that may sign a document; "
                  + offered.size() + " key(s) are for other uses");
    }
    String preferred = System.getProperty(KEY_PROPERTY, "EC");
    return committing.stream()
        .filter(key -> preferred.equals(key.getCertificate().getPublicKey().getAlgorithm()))
        .findFirst()
        .orElse(committing.getFirst());
  }

  /** Whether this certificate may commit its holder to a document. */
  private static boolean commitsContent(DSSPrivateKeyEntry key) {
    boolean[] usage = key.getCertificate().getCertificate().getKeyUsage();
    // Bit 1 of the key-usage extension: non-repudiation, which X.509
    // now calls content commitment.
    return usage != null && usage.length > 1 && usage[1];
  }

  /** The slot to open, first unless this run says otherwise. */
  private static int slotIndex() {
    try {
      return Integer.parseInt(System.getProperty(SLOT_PROPERTY, "0"));
    } catch (NumberFormatException notANumber) {
      return 0;
    }
  }

  /**
   * The authority a signature is stamped by, as this machine is set.
   *
   * <p>Without a timestamp a signature carries only the time this
   * computer claimed, which nobody has to believe and a validator
   * reports as unvalidated. Credentials are attached only when the
   * setting carries them, which is the case for an authority an
   * organization pays for rather than the shared qualified one.
   */
  private static OnlineTSPSource timestamps() {
    TimestampSettings settings = TimestampSettings.stored();
    OnlineTSPSource source = new OnlineTSPSource(settings.address());
    if (settings.hasCredentials()) {
      TimestampDataLoader loader = new TimestampDataLoader();
      loader.addAuthentication(
          settings.host(), settings.port(), "", settings.username(),
          settings.password().toCharArray());
      source.setDataLoader(loader);
    }
    return source;
  }

  /**
   * What the issuer says about this certificate, asked before a
   * signature is made with it.
   *
   * <p>The issuer is asked over OCSP, at the address the certificate
   * itself names. A refusal to answer is not a refusal to sign: the
   * status is reported as unchecked, and the holder decides.
   */
  public CredentialStatus credentialStatus() {
    CertificateToken certificate = key.getCertificate();
    CertificateToken issuer = issuerOf(certificate);
    if (issuer == null) {
      return new CredentialStatus(
          CredentialStatus.State.UNKNOWN, "the issuer's certificate is not on the card");
    }
    try {
      RevocationToken<OCSP> answer =
          new OnlineOCSPSource().getRevocationToken(certificate, issuer);
      if (answer == null || answer.getStatus() == null) {
        return new CredentialStatus(CredentialStatus.State.UNKNOWN, "the issuer did not answer");
      }
      if (answer.getStatus().isGood()) {
        return new CredentialStatus(CredentialStatus.State.USABLE, "");
      }
      String reason = answer.getReason() == null ? "withdrawn" : answer.getReason().getShortName();
      String when =
          answer.getRevocationDate() == null ? "" : " on " + answer.getRevocationDate();
      return new CredentialStatus(CredentialStatus.State.REVOKED, reason + when);
    } catch (RuntimeException unreachable) {
      return new CredentialStatus(
          CredentialStatus.State.UNKNOWN, "the issuer could not be reached");
    }
  }

  /**
   * The issuer's certificate: from the chain the card carries, or from
   * the address the certificate names when the card carries only its
   * own.
   *
   * <p>A FINEID card publishes the holder's certificate and not the
   * CA's, so asking the card alone finds nothing and the status can
   * never be read. The certificate says where its issuer lives, which
   * is what that extension is for.
   */
  private CertificateToken issuerOf(CertificateToken certificate) {
    for (CertificateToken candidate : key.getCertificateChain()) {
      if (!candidate.equals(certificate) && certificate.isSignedBy(candidate)) {
        return candidate;
      }
    }
    try {
      for (CertificateToken fetched : new DefaultAIASource().getCertificatesByAIA(certificate)) {
        if (certificate.isSignedBy(fetched)) {
          return fetched;
        }
      }
    } catch (RuntimeException unreachable) {
      return null;
    }
    return null;
  }

  /**
   * Who the signature will name, as the certificate writes it.
   *
   * <p>The common name rather than the whole distinguished name: a
   * window says who is signing, and the rest of the subject is for the
   * diagnostic report.
   */
  /** Which cryptosystem the chosen key uses, for a report to name. */
  public String keyAlgorithm() {
    return key.getCertificate().getPublicKey().getAlgorithm();
  }

  public String signerName() {
    String subject = key.getCertificate().getSubject().getPrettyPrintRFC2253();
    for (String part : subject.split(",")) {
      String field = part.trim();
      if (field.startsWith("commonName=")) {
        return field.substring("commonName=".length());
      }
    }
    return subject;
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
