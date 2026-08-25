package pro.sketchware.mcp;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class XmlOpsTest {

    @Test
    public void updatesFirstOccurrence() throws IOException {
        String xml = "<TextView text=\"Hello\" size=\"12\" text=\"ignored\"/>";
        String out = XmlOps.updateAttribute(xml, "text", "Login", null);
        assertTrue(out.contains("text=\"Login\""));
    }

    @Test
    public void oldValueMismatchRejected() {
        String xml = "<TextView text=\"Hello\"/>";
        assertThrows(IOException.class, () ->
                XmlOps.updateAttribute(xml, "text", "Login", "Wrong"));
    }

    @Test
    public void missingPropertyRejected() {
        String xml = "<TextView size=\"12\"/>";
        assertThrows(IOException.class, () ->
                XmlOps.updateAttribute(xml, "text", "Login", null));
    }

    @Test
    public void insertBeforeMarker() throws IOException {
        String xml = "<manifest><application/></manifest>";
        String out = XmlOps.insertBefore(xml, "</manifest>",
                "    <uses-permission android:name=\"android.permission.INTERNET\" />\n");
        assertTrue(out.indexOf("uses-permission") < out.indexOf("</manifest>"));
    }
}
