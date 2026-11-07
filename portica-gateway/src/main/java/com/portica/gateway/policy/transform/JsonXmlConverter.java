package com.portica.gateway.policy.transform;

import tools.jackson.databind.json.JsonMapper;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic JSON <-> XML conversion with the JDK's StAX API (no external XML library).
 * JSON is handled as plain Java values (Map/List/String/Number/Boolean/null).
 * XML parsing rejects DTDs and external entities (XXE-safe).
 */
public final class JsonXmlConverter {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final XMLOutputFactory OUTPUT = XMLOutputFactory.newFactory();
    private static final XMLInputFactory INPUT = secureInputFactory();

    private JsonXmlConverter() {
    }

    // ------------------------------------------------------------------ JSON -> XML

    public static byte[] jsonToXml(byte[] json, String rootElement, String arrayItemElement) {
        Object value = readJson(json);
        try {
            StringWriter out = new StringWriter();
            XMLStreamWriter w = OUTPUT.createXMLStreamWriter(out);
            w.writeStartDocument("UTF-8", "1.0");
            if (value instanceof Map<?, ?> map && map.size() == 1 && !(map.values().iterator().next() instanceof List<?>)) {
                // {"order": {...}} becomes <order>...</order>
                Map.Entry<?, ?> only = map.entrySet().iterator().next();
                writeElement(w, xmlName(String.valueOf(only.getKey())), only.getValue(), arrayItemElement);
            } else {
                writeElement(w, rootElement, value, arrayItemElement);
            }
            w.writeEndDocument();
            w.close();
            return out.toString().getBytes(StandardCharsets.UTF_8);
        } catch (XMLStreamException e) {
            throw new TransformException("Could not write XML: " + e.getMessage());
        }
    }

    private static void writeElement(XMLStreamWriter w, String name, Object value, String item) throws XMLStreamException {
        if (value instanceof List<?> list) {
            w.writeStartElement(name);
            for (Object v : list) {
                writeElement(w, item, v, item);
            }
            w.writeEndElement();
            return;
        }
        w.writeStartElement(name);
        writeContent(w, value, item);
        w.writeEndElement();
    }

    private static void writeContent(XMLStreamWriter w, Object value, String item) throws XMLStreamException {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String child = xmlName(String.valueOf(e.getKey()));
                if (e.getValue() instanceof List<?> list) {
                    for (Object v : list) { // arrays become repeated elements
                        if (v instanceof List<?>) {
                            writeElement(w, child, v, item);
                        } else {
                            w.writeStartElement(child);
                            writeContent(w, v, item);
                            w.writeEndElement();
                        }
                    }
                } else {
                    w.writeStartElement(child);
                    writeContent(w, e.getValue(), item);
                    w.writeEndElement();
                }
            }
        } else if (value instanceof List<?> list) {
            for (Object v : list) {
                writeElement(w, item, v, item);
            }
        } else if (value != null) {
            w.writeCharacters(String.valueOf(value));
        }
    }

    /** Makes any JSON key a legal XML element name. */
    static String xmlName(String key) {
        if (key.isEmpty()) {
            return "_";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
            sb.append(ok ? c : '_');
        }
        char first = sb.charAt(0);
        if (!(Character.isLetter(first) || first == '_')) {
            sb.insert(0, '_');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ XML -> JSON

    public static byte[] xmlToJson(byte[] xml, boolean recognizeNumbers, boolean recognizeBooleans) {
        try {
            XMLStreamReader r = INPUT.createXMLStreamReader(new ByteArrayInputStream(xml));
            while (r.hasNext() && r.next() != XMLStreamConstants.START_ELEMENT) {
                // skip prolog and comments
            }
            if (r.getEventType() != XMLStreamConstants.START_ELEMENT) {
                throw new TransformException("XML has no root element");
            }
            String root = r.getLocalName();
            Object value = readElement(r, recognizeNumbers, recognizeBooleans);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put(root, value);
            return JSON.writeValueAsBytes(result);
        } catch (XMLStreamException e) {
            throw new TransformException("Invalid XML: " + e.getMessage());
        }
    }

    /** Reader positioned on START_ELEMENT; returns when positioned on its END_ELEMENT. */
    private static Object readElement(XMLStreamReader r, boolean numbers, boolean booleans) throws XMLStreamException {
        Map<String, Object> object = new LinkedHashMap<>();
        for (int i = 0; i < r.getAttributeCount(); i++) {
            object.put("@" + r.getAttributeLocalName(i), scalar(r.getAttributeValue(i), numbers, booleans));
        }
        StringBuilder text = new StringBuilder();
        boolean hasChildren = false;
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                hasChildren = true;
                String name = r.getLocalName();
                Object child = readElement(r, numbers, booleans);
                Object existing = object.get(name);
                if (existing == null && !object.containsKey(name)) {
                    object.put(name, child);
                } else if (existing instanceof RepeatedList list) {
                    list.add(child);
                } else {
                    RepeatedList list = new RepeatedList();
                    list.add(existing);
                    list.add(child);
                    object.put(name, list);
                }
            } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                text.append(r.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            }
        }
        String t = text.toString().strip();
        if (!hasChildren && object.isEmpty()) {
            return t.isEmpty() ? null : scalar(t, numbers, booleans);
        }
        if (!t.isEmpty()) {
            object.put("#text", scalar(t, numbers, booleans));
        }
        return object;
    }

    /** Marks lists created from repeated elements (vs. values that happen to be lists). */
    private static final class RepeatedList extends ArrayList<Object> {
    }

    private static Object scalar(String s, boolean numbers, boolean booleans) {
        if (booleans && (s.equals("true") || s.equals("false"))) {
            return Boolean.valueOf(s);
        }
        if (numbers && s.matches("-?(0|[1-9]\\d{0,17})(\\.\\d+)?")) {
            return s.contains(".") ? (Object) Double.valueOf(s) : (Object) Long.valueOf(s);
        }
        return s;
    }

    // ------------------------------------------------------------------ helpers

    public static Object readJson(byte[] json) {
        try {
            return JSON.readValue(json, Object.class);
        } catch (RuntimeException e) {
            throw new TransformException("Invalid JSON");
        }
    }

    private static XMLInputFactory secureInputFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_COALESCING, true);
        return f;
    }

    /** Conversion failed because the input was not valid. */
    public static final class TransformException extends RuntimeException {

        public TransformException(String message) {
            super(message, null, false, false);
        }
    }

}
