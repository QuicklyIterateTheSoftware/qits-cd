package eu.wohlben.qits.cd.error;

/** 404. */
public class NotFoundException extends CdException {

  public NotFoundException(String message) {
    super(404, message);
  }
}
