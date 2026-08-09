package fi.refineid.signer.job;

/**
 * How often the holder types PIN 2 for a job.
 *
 * <p>Not how often the card verifies it. The card verifies PIN 2
 * before every signature and never carries one verification into the
 * next -- that part is the card's and cannot be changed. What software
 * decides is where the value comes from each time: from a person, or
 * from a value that person entered once for this job.
 */
public enum PinPolicy {

  /**
   * The system dialog collects PIN 2, and it appears for every
   * signature.
   *
   * <p>The value never enters this process, which is the reason to
   * prefer it. Signing twelve documents separately means answering
   * twelve dialogs.
   */
  ASK_EACH_SIGNATURE,

  /**
   * The holder types PIN 2 once and this job signs with it.
   *
   * <p>The card still verifies before each signature; the value is
   * presented from memory rather than from a person. It is held for
   * the length of one job and no longer, and it requires the module to
   * be running with textual PIN entry, which is what withdraws the
   * protected-authentication-path flag and lets a caller supply the
   * PIN at all.
   */
  ASK_ONCE_FOR_THE_JOB
}
