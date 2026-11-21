package org.ruyisdk.imager;

import java.net.URI;
import java.util.List;

final class Policy {
    static final String SITE = "https://ruyisdk.org";
    static final String STREAM = SITE + "/.ruyi-android/";

    static boolean page(String value) {
        try {
            URI url = URI.create(value);
            return "https".equals(url.getScheme()) && "ruyisdk.org".equals(url.getRawAuthority())
                    && url.getRawPath().matches("/(?:(?:de|en|zh-Hans)/)?tryruyi/(?:imager|device)/?");
        } catch (RuntimeException invalid) { return false; }
    }

    static URI mirror(String value) {
        URI url = URI.create(value);
        if (!("https".equals(url.getScheme()) || "http".equals(url.getScheme()))
                || url.getHost() == null || url.getRawUserInfo() != null)
            throw new IllegalArgumentException("Invalid mirror URL");
        return url;
    }

    static void path(List<String> parts) {
        if (parts.isEmpty() || parts.size() > 64 || !parts.get(0).equals("RuyiImages"))
            throw new IllegalArgumentException("Invalid workspace path");
        for (String part : parts)
            if (part.isEmpty() || part.length() > 255 || part.equals(".") || part.equals("..")
                    || part.matches("(?s).*[\\\\/:\\x00-\\x1f].*"))
                throw new IllegalArgumentException("Invalid workspace path component");
    }
}
