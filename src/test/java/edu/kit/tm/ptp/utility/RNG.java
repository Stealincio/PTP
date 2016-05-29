package edu.kit.tm.ptp.utility;

import java.nio.charset.Charset;
import java.util.Random;


/**
 * This class wraps a RNG and provides utility methods for generating a random string and a random
 * number.
 *
 * @author Simeon Andreev
 *
 */
public class RNG {

  /** The underlying RNG. */
  private Random random = new Random();


  /**
   * Returns a random integer with value within the given value bounds.
   *
   * @param minValue Minimum value of the random integer.
   * @param maxValue Maximum value of the random integer.
   * @return The generated random integer.
   */
  public int integer(int minValue, int maxValue) {
    return minValue + random.nextInt(maxValue - minValue + 1);
  }

  /**
   * Returns a random string with length within the given lengh bounds.
   *
   * @param minLength Minimum length for the random string.
   * @param maxLength Maximum length for the random string.
   * @param charset The charset to use.
   * @return The generated random string.
   */
  public String string(int minLength, int maxLength, Charset charset) {
    // Create the random string. Choose random length within the length bounds and choose random
    // bytes.
    final int length = minLength + random.nextInt(maxLength - minLength + 1);
    byte[] buffer = new byte[length];
    random.nextBytes(buffer);
    return new String(buffer, charset);
  }

  /**
   * Returns a random double in [0.0, 1.0].
   *
   * @return A random double in [0.0, 1.0].
   */
  public double floating() {
    return random.nextDouble();
  }

}
