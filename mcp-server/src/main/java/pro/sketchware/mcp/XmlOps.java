package pro.sketchware.mcp;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, defensive XML helpers for property and manifest updates. */
final class XmlOps {

    private XmlOps() {
    }

    /**
     * Updates occurrences of {@code prop="old"} to {@code prop="new"} inside
     * attribute-style properties. If oldValue is null the first occurrence is
     * replaced; if nothing matches and no oldValue was given, an error is
     * thrown (never blindly append).
     */
    static String updateAttribute(String xml, String prop, String value, String oldValue)
            throws IOException {
        String regex = "(" + Pattern.quote(prop) + "\\s*=\\s*\")([^\"]*)(\")";
        Matcher m = Pattern.compile(regex).matcher(xml);
        if (!m.find()) {
            throw new IOException("Attribute \"" + prop + "\" not found in file.");
        }
        if (oldValue != null && !m.group(2).equals(oldValue)) {
            throw new IOException("Current value \"" + m.group(2)
                    + "\" does not match expected oldValue \"" + oldValue + "\".");
        }
        return m.replaceFirst(Matcher.quoteReplacement(prop + "=\"" + value + "\""));
    }

    /** Inserts content before a marker (e.g. </manifest>). */
    static String insertBefore(String xml, String marker, String content)
            throws IOException {
        int idx = xml.indexOf(marker);
        if (idx < 0) {
            throw new IOException("Marker " + marker + " not found.");
        }
        return xml.substring(0, idx) + content + xml.substring(idx);
    }
}
