package sample;

/**
 * Created by freemanliu on 3/19/18.
 */
public class AlienSource {


  private final String v;

  public AlienSource(String v) {
    this.v = v;
  }

  public Alien getAlien() {
    return new Alien(v);
  }
}
