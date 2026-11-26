package cl.etimo.initiumdb.core;

import java.io.FilterWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DBUnit's {@code XmlWriter} escapes text one UTF-16 unit at a time, so a character outside the
 * Basic Multilingual Plane (an emoji, for example) comes out as two references to surrogates, such
 * as {@code &#55357;&#56832;}, which XML forbids. This writer joins each such pair into one
 * reference to the character ({@code &#128512;}) and passes everything else through unchanged. Only
 * text that starts with {@code &} is held back, and never more than one pair.
 */
final class SurrogatePairWriter extends FilterWriter {

  private static final Pattern PAIR = Pattern.compile("&#(\\d{5});&#(\\d{5});");

  private final StringBuilder pending = new StringBuilder();

  SurrogatePairWriter(Writer out) {
    super(out);
  }

  @Override
  public void write(int c) throws IOException {
    if (pending.isEmpty() && c != '&') {
      out.write(c);
      return;
    }
    pending.append((char) c);
    drain();
  }

  @Override
  public void write(char[] buffer, int offset, int length) throws IOException {
    for (int i = offset; i < offset + length; i++) {
      write(buffer[i]);
    }
  }

  @Override
  public void write(String text, int offset, int length) throws IOException {
    for (int i = offset; i < offset + length; i++) {
      write(text.charAt(i));
    }
  }

  private void drain() throws IOException {
    while (!pending.isEmpty()) {
      Matcher m = PAIR.matcher(pending);
      if (m.matches()) {
        int high = Integer.parseInt(m.group(1));
        int low = Integer.parseInt(m.group(2));
        if (high <= Character.MAX_VALUE
            && low <= Character.MAX_VALUE
            && Character.isHighSurrogate((char) high)
            && Character.isLowSurrogate((char) low)) {
          out.write("&#" + Character.toCodePoint((char) high, (char) low) + ";");
          pending.setLength(0);
          return;
        }
      } else if (m.hitEnd()) {
        return;
      }
      // Not a pair: release the first character and look again at what follows it
      out.write(pending.charAt(0));
      pending.deleteCharAt(0);
      while (!pending.isEmpty() && pending.charAt(0) != '&') {
        out.write(pending.charAt(0));
        pending.deleteCharAt(0);
      }
    }
  }

  @Override
  public void close() throws IOException {
    out.write(pending.toString());
    pending.setLength(0);
    super.close();
  }
}
