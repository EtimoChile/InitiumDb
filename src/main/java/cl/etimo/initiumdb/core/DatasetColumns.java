package cl.etimo.initiumdb.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.dbunit.database.AmbiguousTableNameException;
import org.dbunit.dataset.Column;
import org.dbunit.dataset.DataSetException;
import org.dbunit.dataset.DefaultDataSet;
import org.dbunit.dataset.DefaultTable;
import org.dbunit.dataset.DefaultTableMetaData;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.datatype.DataType;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DeclHandler;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The columns of each table of a Flat XML dataset, which Flat XML cannot state on its own because
 * it omits NULL values: a column that is NULL in every row would never appear. Capture records
 * every selected column in an internal DTD ({@code <!ATTLIST>}); {@link #scan} reads them back
 * together with the attributes of the rows (so datasets written without the DTD keep working) in a
 * streaming pass whose memory is bounded by the number of columns, never by the number of rows.
 */
final class DatasetColumns {

  private DatasetColumns() {}

  /** Copies {@code body} to {@code target}, adding the internal DTD after the XML declaration. */
  static void writeWithDtd(Path body, Path target, Map<String, Set<String>> columns)
      throws IOException {
    try (InputStream in = new BufferedInputStream(Files.newInputStream(body));
        OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
      int previous = -1;
      int b;
      while ((b = in.read()) != -1) {
        out.write(b);
        if (previous == '?' && b == '>') {
          break;
        }
        previous = b;
      }
      StringBuilder dtd = new StringBuilder("\n<!DOCTYPE dataset [\n");
      columns.forEach(
          (table, names) -> {
            if (!names.isEmpty()) {
              dtd.append("<!ATTLIST ").append(table);
              names.forEach(name -> dtd.append(' ').append(name).append(" CDATA #IMPLIED"));
              dtd.append(">\n");
            }
          });
      dtd.append("]>");
      out.write(dtd.toString().getBytes(StandardCharsets.UTF_8));
      in.transferTo(out);
    }
  }

  /** Reads the whole file once, streaming, and returns the metadata DBUnit needs to produce it. */
  static IDataSet scan(Path file) throws DataSetException {
    Map<String, Set<String>> columns = new LinkedHashMap<>();
    Handler handler = new Handler(columns);
    try {
      SAXParserFactory factory = SAXParserFactory.newInstance();
      factory.setNamespaceAware(false);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      SAXParser parser = factory.newSAXParser();
      parser.setProperty("http://xml.org/sax/properties/declaration-handler", handler);
      try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
        InputSource source = new InputSource(in);
        source.setSystemId(file.toUri().toString());
        parser.parse(source, handler);
      }
    } catch (SAXException | ParserConfigurationException | IOException e) {
      throw new DataSetException(
          "Dataset " + file + " is not valid Flat XML: " + e.getMessage(), e);
    }
    DefaultDataSet metadata = new DefaultDataSet(true);
    try {
      for (Map.Entry<String, Set<String>> table : columns.entrySet()) {
        List<Column> list = new ArrayList<>();
        table.getValue().forEach(name -> list.add(new Column(name, DataType.UNKNOWN)));
        metadata.addTable(
            new DefaultTable(
                new DefaultTableMetaData(table.getKey(), list.toArray(new Column[0]))));
      }
    } catch (AmbiguousTableNameException e) {
      throw new DataSetException(e);
    }
    return metadata;
  }

  private static final class Handler extends DefaultHandler implements DeclHandler {
    private final Map<String, Set<String>> columns;
    private int depth;

    Handler(Map<String, Set<String>> columns) {
      this.columns = columns;
    }

    @Override
    public void attributeDecl(String element, String name, String type, String mode, String value) {
      columns.computeIfAbsent(element, k -> new LinkedHashSet<>()).add(name);
    }

    @Override
    public void startElement(String uri, String local, String qName, Attributes attributes) {
      depth++;
      if (depth == 2) {
        Set<String> names = columns.computeIfAbsent(qName, k -> new LinkedHashSet<>());
        for (int i = 0; i < attributes.getLength(); i++) {
          names.add(attributes.getQName(i));
        }
      }
    }

    @Override
    public void endElement(String uri, String local, String qName) {
      depth--;
    }

    @Override
    public void elementDecl(String name, String model) {}

    @Override
    public void internalEntityDecl(String name, String value) {}

    @Override
    public void externalEntityDecl(String name, String publicId, String systemId) {}
  }
}
