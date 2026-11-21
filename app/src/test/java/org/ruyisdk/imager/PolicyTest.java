package org.ruyisdk.imager;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class PolicyTest {
    @Test public void onlyProvisioningRoutesOwnTheBridge() {
        assertTrue(Policy.page("https://ruyisdk.org/en/tryruyi/device/"));
        assertTrue(Policy.page("https://ruyisdk.org/tryruyi/imager"));
        for (String value : List.of("https://ruyisdk.org/help", "https://example.org/tryruyi/device",
                "http://ruyisdk.org/tryruyi/device", "https://user@ruyisdk.org/tryruyi/device",
                "https://ruyisdk.org/tryruyi%2fdevice")) assertFalse(Policy.page(value));
    }
    @Test public void restrictsImagePathsAndDownloadSchemes() {
        Policy.path(List.of("RuyiImages", "image.bin"));
        for (String value : List.of("", ".", "..", "a/b", "a\\b", "a:b", "a\nb"))
            assertThrows(IllegalArgumentException.class, () -> Policy.path(List.of("RuyiImages", value)));
        assertThrows(IllegalArgumentException.class, () -> Policy.path(List.of("Pictures", "file")));
        for (String value : List.of("file:///data/data", "content://provider/document", "https://user:pass@mirror.example/a"))
            assertThrows(IllegalArgumentException.class, () -> Policy.mirror(value));
        assertEquals("mirror.example", Policy.mirror("https://mirror.example/board.zip?token=value").getHost());
    }
}
