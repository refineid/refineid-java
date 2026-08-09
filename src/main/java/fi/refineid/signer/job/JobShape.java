package fi.refineid.signer.job;

/**
 * The two honest ways to sign several documents.
 *
 * <p>They differ in what they produce and in what they cost the
 * holder, and neither is a setting to be defaulted quietly: one
 * signature over a container is not the same legal object as one
 * signature per document, and the holder is the only one who knows
 * which was meant.
 */
public enum JobShape {

  /**
   * One ASiC-E container holding every document, signed once.
   *
   * <p>One signature, so the card asks once however many documents
   * went in. This is the answer for anyone who wants to authorize a
   * batch as a batch.
   */
  ONE_CONTAINER,

  /**
   * Each document signed on its own, keeping its own file.
   *
   * <p>One signature each, so the card asks once per document. A PDF
   * keeps being a PDF and carries its signature inside it; anything
   * else is wrapped in a container of its own, because only a PDF can
   * hold a PAdES signature.
   */
  EACH_DOCUMENT
}
