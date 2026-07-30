package eu.wohlben.qits.cd.error;

/** 400. */
public class BadRequestException extends CdException {

  public BadRequestException(String message) {
    super(400, message);
  }
}
