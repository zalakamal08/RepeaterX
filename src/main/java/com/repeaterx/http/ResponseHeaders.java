package com.repeaterx.http;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mutable response header map. */
public class ResponseHeaders {
    private final Map<String, String> map = new LinkedHashMap<>();

    public void set(String name, String value) { map.put(name, value); }
    public String get(String name)             { return map.get(name); }

    Iterable<Map.Entry<String, String>> entries() { return map.entrySet(); }
}
