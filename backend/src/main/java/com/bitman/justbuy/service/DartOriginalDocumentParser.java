package com.bitman.justbuy.service;

import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.ZipInputStream;

final class DartOriginalDocumentParser {
    record ParsedFacts(Map<String, String> facts, List<String> unknownFields) {}
    private static final int MAX_COMPRESSED = 1_048_576;
    private static final int MAX_ENTRY = 1_048_576;
    private static final int MAX_EXPANDED = 4 * 1_048_576;

    static ParsedFacts parse(byte[] zip, String eventType) {
        if (zip == null || zip.length < 4 || zip.length > MAX_COMPRESSED
            || zip[0] != 'P' || zip[1] != 'K' || zip[2] != 3 || zip[3] != 4)
            throw new IllegalArgumentException("INVALID_ORIGINAL_DOCUMENT");
        Map<String, String> labels = "SHAREHOLDER_CHANGE".equals(eventType)
            ? Map.of("보유비율(%)", "ownershipPct", "증감비율(%)", "ownershipChangePct")
            : Map.of("계약금액(원)", "contractAmountWon", "계약기간", "contractPeriod");
        Map<String, List<String>> values = new LinkedHashMap<>();
        labels.values().stream().sorted().forEach(key -> values.put(key, new ArrayList<>()));
        int entries = 0, expanded = 0, xmlEntries = 0;
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (++entries > 8 || unsafeName(entry.getName())) throw new IllegalArgumentException("UNSAFE_ARCHIVE");
                var output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IllegalArgumentException("INTERRUPTED");
                    expanded += count;
                    if (output.size() + count > MAX_ENTRY || expanded > MAX_EXPANDED)
                        throw new IllegalArgumentException("ORIGINAL_DOCUMENT_TOO_LARGE");
                    output.write(buffer, 0, count);
                }
                if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".xml")) {
                    xmlEntries++;
                    collectValues(output.toByteArray(), labels, values);
                }
                input.closeEntry();
            }
            if (xmlEntries == 0) throw new IllegalArgumentException("NO_ORIGINAL_XML");
        } catch (Exception error) {
            throw new IllegalArgumentException("INVALID_ORIGINAL_DOCUMENT");
        }
        Map<String, String> facts = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        values.forEach((field, candidates) -> {
            if (candidates.size() == 1 && validValue(field, candidates.getFirst())) facts.put(field, candidates.getFirst());
            else unknown.add(field);
        });
        return new ParsedFacts(Collections.unmodifiableMap(facts), List.copyOf(unknown));
    }

    private static boolean unsafeName(String name) {
        return name == null || name.isBlank() || name.startsWith("/") || name.contains("\\") || name.contains(":")
            || Arrays.stream(name.split("/", -1)).anyMatch(part -> "..".equals(part) || ".".equals(part));
    }

    private static void collectValues(byte[] xml, Map<String, String> labels, Map<String, List<String>> values) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("EXTERNAL_ENTITY_DISABLED"); });
        builder.setErrorHandler(new DefaultHandler() {
            @Override public void error(SAXParseException error) throws SAXException { throw error; }
            @Override public void fatalError(SAXParseException error) throws SAXException { throw error; }
        });
        var document = builder.parse(new ByteArrayInputStream(xml));
        var rows = document.getElementsByTagName("TR");
        for (int rowIndex = 0; rowIndex < rows.getLength(); rowIndex++) {
            List<String> cells = new ArrayList<>();
            var children = rows.item(rowIndex).getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element cell && ("TD".equals(cell.getTagName()) || "TH".equals(cell.getTagName()))) {
                    cells.add(cell.getTextContent().replaceAll("\\s+", " ").trim());
                }
            }
            for (int i = 0; i + 1 < cells.size(); i++) {
                String field = labels.get(cells.get(i).replaceAll("\\s+", ""));
                if (field != null) {
                    // Multiple cells after a label do not establish one unambiguous amount/period.
                    values.get(field).add(i + 2 == cells.size() ? cells.get(i + 1) : "");
                }
            }
        }
    }

    private static boolean validValue(String field, String raw) {
        if (raw == null || raw.length() > 200) return false;
        if ("contractPeriod".equals(field)) {
            String[] dates = raw.split("\\s*~\\s*", -1);
            if (dates.length != 2) return false;
            try { return !LocalDate.parse(dates[1]).isBefore(LocalDate.parse(dates[0])); }
            catch (RuntimeException error) { return false; }
        }
        boolean money = "contractAmountWon".equals(field);
        String numeric = money ? "(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)"
            : "-?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?";
        if (!raw.matches(numeric + (money ? "(?:\\s*원)?" : "(?:\\s*%)?"))) return false;
        String value = raw.replaceFirst(money ? "\\s*원$" : "\\s*%$", "");
        try {
            BigDecimal number = new BigDecimal(value.replace(",", ""));
            if (number.precision() > 32) return false;
            return money ? number.signum() >= 0 : number.abs().compareTo(BigDecimal.valueOf(100)) <= 0
                && (!"ownershipPct".equals(field) || number.signum() >= 0);
        } catch (RuntimeException error) { return false; }
    }
}
