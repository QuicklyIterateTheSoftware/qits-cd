package eu.wohlben.qits.cd.error;

/** 409. */
public class ConflictException extends CdException {

  public ConflictException(String message) {
    super(409, message);
  }
}
